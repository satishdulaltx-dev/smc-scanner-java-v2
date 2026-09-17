package com.smcscanner.research;

import com.smcscanner.backtest.*;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Research-only replay of the published SPY time-of-day noise-boundary strategy. */
@Service
public class SpyMomentumResearchService {
    private static final ZoneId ET=ZoneId.of("America/New_York");
    private static final DateTimeFormatter DISPLAY=DateTimeFormatter.ofPattern("MM/dd HH:mm 'ET'");
    private static final int MINUTES=390;
    private static final double HALF_SPREAD=.0005;
    private final PolygonClient polygon;
    public SpyMomentumResearchService(PolygonClient polygon){this.polygon=polygon;}

    public Map<String,Object> run(LocalDate start,LocalDate end){return run(start,end,"CURRENT_BOUNDARY_VWAP");}

    public Map<String,Object> run(LocalDate start,LocalDate end,String stopRule){
        if(!Set.of("CURRENT_BOUNDARY_VWAP","OPPOSITE_BOUNDARY").contains(stopRule))throw new IllegalArgumentException("Unsupported SPY momentum stop rule");
        new BacktestRun(start,end,"spy-noise-momentum-1m",Set.of(),390,2);
        List<OHLCV> bars;polygon.beginHistoricalSession();
        try{bars=polygon.getHistoricalBars("SPY","1m",start.minusDays(35),end);}finally{polygon.endHistoricalSession();}
        SessionSet sessionSet=sessionSet(bars);TreeMap<LocalDate,List<OHLCV>> sessions=sessionSet.complete();List<LocalDate> dates=new ArrayList<>(sessions.keySet());
        List<BacktestService.TradeResult> trades=new ArrayList<>();List<Map<String,Object>> audit=new ArrayList<>();List<String> warnings=new ArrayList<>();int skipped=0;
        LocalDate effectiveStart=null,effectiveEnd=null;
        for(int di=0;di<dates.size();di++){
            LocalDate date=dates.get(di);if(date.isBefore(start)||date.isAfter(end))continue;if(di<14){skipped++;continue;}
            List<List<OHLCV>> prior=new ArrayList<>();for(int i=di-14;i<di;i++)prior.add(sessions.get(dates.get(i)));
            if(prior.stream().anyMatch(s->s.size()!=MINUTES)){skipped++;continue;}
            double previousClose=sessions.get(dates.get(di-1)).get(MINUTES-1).getClose();
            if(effectiveStart==null)effectiveStart=date;effectiveEnd=date;
            trades.addAll(replayDay(date,sessions.get(date),prior,previousClose,audit,"CURRENT_BOUNDARY_VWAP".equals(stopRule)));
        }
        if(sessionSet.incomplete()>0)warnings.add(sessionSet.incomplete()+" observed sessions were excluded because all 390 regular-session one-minute bars were not present");
        if(effectiveStart!=null&&effectiveStart.isAfter(start))warnings.add("Requested start "+start+" was not fully testable; the effective test begins "+effectiveStart);
        if(effectiveEnd!=null&&effectiveEnd.isBefore(end))warnings.add("Requested end "+end+" was not fully testable; the effective test ends "+effectiveEnd);
        warnings.add("Underlying SPY replay only; historical option quotes are unavailable");
        trades.sort(Comparator.comparingLong(BacktestService.TradeResult::entryEpochMs));
        ResearchStatistics.Summary s=ResearchStatistics.summarize(trades);Map<String,Object> out=new LinkedHashMap<>();
        long wins=trades.stream().filter(t->t.pnlPct()>0).count(),losses=trades.stream().filter(t->t.pnlPct()<0).count();
        out.put("ticker","SPY TIME-OF-DAY MOMENTUM");out.put("research",true);out.put("portfolio",false);
        out.put("pattern","spy-noise-momentum-1m");out.put("decision_timeframe","1m / half-hour decisions");
        out.put("start_date",start.toString());out.put("end_date",end.toString());out.put("lookback_days",ChronoUnit.DAYS.between(start,end)+1);
        out.put("exit_style",stopRule);out.put("max_hold_minutes",390);out.put("target_r",0);
        out.put("round_trip_cost_bps",10.0);out.put("filters","none");out.put("return_unit","1R = 1% SPY move");
        out.put("total_trades",trades.size());out.put("wins",wins);out.put("losses",losses);out.put("timeouts",0);out.put("be_stops",0);
        out.put("win_rate",trades.isEmpty()?0:wins*100.0/trades.size());out.put("expectancy",average(trades,false));
        out.put("avg_win_pct",average(trades,true));out.put("avg_loss_pct",average(trades.stream().filter(t->t.pnlPct()<0).toList(),false));
        out.put("mean_r",s.meanR());out.put("total_r",s.totalR());out.put("profit_factor",s.profitFactor());
        out.put("mean_r_ci_low",s.ciLowR());out.put("mean_r_ci_high",s.ciHighR());out.put("positive_months",s.positiveMonths());out.put("active_months",s.activeMonths());
        out.put("research_verdict",s.verdict());out.put("research_explanation",s.explanation());
        Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("observed_sessions",sessionSet.observed());coverage.put("complete_sessions",dates.size());coverage.put("incomplete_sessions",sessionSet.incomplete());coverage.put("skipped_test_sessions",skipped);coverage.put("effective_start_date",effectiveStart==null?"":effectiveStart.toString());coverage.put("effective_end_date",effectiveEnd==null?"":effectiveEnd.toString());out.put("coverage",Map.copyOf(coverage));
        out.put("warnings",List.copyOf(warnings));
        out.put("decision_audit",audit);out.put("trades",trades.stream().map(this::tradeMap).toList());
        out.put("filtered_total",0);out.put("quality_filtered",0);out.put("ctx_filtered",0);out.put("news_filtered",0);out.put("execution_costs_measured",false);out.put("disabled",false);
        out.put("opt_total_pnl",0);out.put("opt_avg_win_pnl",0);out.put("opt_avg_loss_pnl",0);out.put("opt_expectancy",0);out.put("opt_total_return",0);out.put("avg_contracts",0);
        for(String k:List.of("bucket_85plus_total","bucket_85plus_wins","bucket_85plus_wr","bucket_85plus_exp","bucket_75to84_total","bucket_75to84_wins","bucket_75to84_wr","bucket_75to84_exp","bucket_below75_total","bucket_below75_wins","bucket_below75_wr","bucket_below75_exp"))out.put(k,0);
        return out;
    }

    List<BacktestService.TradeResult> replayDay(LocalDate date,List<OHLCV> day,List<List<OHLCV>> prior,double previousClose,List<Map<String,Object>> audit){
        return replayDay(date,day,prior,previousClose,audit,true);
    }

    List<BacktestService.TradeResult> replayDay(LocalDate date,List<OHLCV> day,List<List<OHLCV>> prior,double previousClose,List<Map<String,Object>> audit,boolean tightStop){
        List<BacktestService.TradeResult> result=new ArrayList<>();Position position=null;
        for(int index=29;index<=359;index+=30){
            int decisionIndex=index;
            OHLCV decision=day.get(index),next=day.get(index+1);double sigma=prior.stream().mapToDouble(x->Math.abs(x.get(decisionIndex).getClose()/x.get(0).getOpen()-1)).average().orElseThrow();
            double open=day.get(0).getOpen(),upper=Math.max(open,previousClose)*(1+sigma),lower=Math.min(open,previousClose)*(1-sigma),vwap=vwap(day,index),close=decision.getClose();
            String signal=close>upper?"long":close<lower?"short":"flat";Map<String,Object> row=new LinkedHashMap<>();
            row.put("date",date.toString());row.put("decision_ts",decision.getTimestamp()+60_000);row.put("close",close);row.put("upper",upper);row.put("lower",lower);row.put("vwap",vwap);row.put("signal",signal);row.put("position_before",position==null?"flat":position.direction());
            if(position==null){if(!"flat".equals(signal))position=enter(signal,next.getOpen(),next.getTimestamp(),upper,lower,vwap);}
            else if("long".equals(position.direction())){
                if(close<lower){result.add(exit(position,next.getOpen(),next.getTimestamp(),"REVERSE"));position=enter("short",next.getOpen(),next.getTimestamp(),upper,lower,vwap);}
                else if(tightStop&&close<Math.max(upper,vwap)){result.add(exit(position,next.getOpen(),next.getTimestamp(),"TRAIL_EXIT"));position=null;}
            }else{
                if(close>upper){result.add(exit(position,next.getOpen(),next.getTimestamp(),"REVERSE"));position=enter("long",next.getOpen(),next.getTimestamp(),upper,lower,vwap);}
                else if(tightStop&&close>Math.min(lower,vwap)){result.add(exit(position,next.getOpen(),next.getTimestamp(),"TRAIL_EXIT"));position=null;}
            }
            row.put("position_after",position==null?"flat":position.direction());audit.add(Map.copyOf(row));
        }
        if(position!=null)result.add(exit(position,day.get(MINUTES-1).getClose(),day.get(MINUTES-1).getTimestamp()+60_000,"EOD"));return result;
    }

    private Position enter(String dir,double market,long ts,double upper,double lower,double vwap){double fill="long".equals(dir)?market*(1+HALF_SPREAD):market*(1-HALF_SPREAD);return new Position(dir,fill,ts,upper,lower,vwap);}
    private BacktestService.TradeResult exit(Position p,double market,long ts,String reason){
        double fill="long".equals(p.direction())?market*(1-HALF_SPREAD):market*(1+HALF_SPREAD);double pnl="long".equals(p.direction())?(fill/p.entry()-1)*100:(p.entry()/fill-1)*100;
        double stop="long".equals(p.direction())?p.entry()*.99:p.entry()*1.01;return new BacktestService.TradeResult("SPY",p.direction(),"spy-noise-momentum-1m",p.entry(),stop,0,pnl>0?"WIN":"LOSS",pnl,display(p.timestamp()),display(ts),p.timestamp(),ts,String.format(Locale.US,"noise-boundary | upper=%.4f | lower=%.4f | vwap=%.4f | exit=%s",p.upper(),p.lower(),p.vwap(),reason),0,0,0,null,0,null,0,null,0,0,0,0,1);
    }
    private Map<String,Object> tradeMap(BacktestService.TradeResult t){Map<String,Object> x=new LinkedHashMap<>();x.put("ticker","SPY");x.put("dir",t.direction());x.put("entry",t.entry());x.put("sl",t.sl());x.put("tp",0);x.put("outcome",t.outcome());x.put("pnl_pct",t.pnlPct());x.put("risk_multiple",t.riskMultiple());x.put("entry_time",t.entryTime());x.put("exit_time",t.exitTime());x.put("entry_ts",t.entryEpochMs());x.put("exit_ts",t.exitEpochMs());x.put("pattern",t.strategy());x.put("confidence",0);x.put("atr",0);x.put("factor_breakdown",t.factorBreakdown());return Map.copyOf(x);}
    private static double average(List<BacktestService.TradeResult> t,boolean positive){return t.stream().filter(x->!positive||x.pnlPct()>0).mapToDouble(BacktestService.TradeResult::pnlPct).average().orElse(0);}
    private static double vwap(List<OHLCV> b,int through){double pv=0,v=0;for(int i=0;i<=through;i++){OHLCV x=b.get(i);pv+=(x.getHigh()+x.getLow()+x.getClose())/3*x.getVolume();v+=x.getVolume();}return pv/v;}
    private static SessionSet sessionSet(List<OHLCV> bars){TreeMap<LocalDate,List<OHLCV>> observed=new TreeMap<>();for(OHLCV b:bars){var z=Instant.ofEpochMilli(b.getTimestamp()).atZone(ET);LocalTime t=z.toLocalTime();if(!t.isBefore(LocalTime.of(9,30))&&t.isBefore(LocalTime.of(16,0)))observed.computeIfAbsent(z.toLocalDate(),k->new ArrayList<>()).add(b);}observed.values().forEach(v->v.sort(Comparator.comparingLong(OHLCV::getTimestamp)));TreeMap<LocalDate,List<OHLCV>> complete=new TreeMap<>();observed.forEach((date,rows)->{if(complete(date,rows))complete.put(date,rows);});return new SessionSet(complete,observed.size(),observed.size()-complete.size());}
    private static boolean complete(LocalDate d,List<OHLCV> rows){if(rows.size()!=MINUTES)return false;long ts=d.atTime(9,30).atZone(ET).toInstant().toEpochMilli();for(OHLCV b:rows){if(b.getTimestamp()!=ts)return false;ts+=60_000;}return true;}
    private static String display(long ts){return Instant.ofEpochMilli(ts).atZone(ET).format(DISPLAY);}
    private record Position(String direction,double entry,long timestamp,double upper,double lower,double vwap){}
    private record SessionSet(TreeMap<LocalDate,List<OHLCV>> complete,int observed,int incomplete){}
}
