import com.fasterxml.jackson.databind.*;
import com.smcscanner.model.*;
import com.smcscanner.strategy.*;
import com.smcscanner.backtest.BacktestService;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.lang.reflect.*;

/** Fixed research protocol: next-minute entry, 2R, 30m, first passing setup/day.
 * Stock-price diagnostics only; no option returns, optimization or validation data. */
public class FocusedRetestAudit {
    static final ZoneId ET=ZoneId.of("America/New_York");
    static double value(Object r,String name) throws Exception {
        var method=r.getClass().getDeclaredMethod(name);method.setAccessible(true);return (double)method.invoke(r);
    }
    static OHLCV aggregate(List<OHLCV> rows) {
        return OHLCV.builder().timestamp(rows.get(0).getTimestamp()).open(rows.get(0).getOpen())
            .high(rows.stream().mapToDouble(OHLCV::getHigh).max().orElseThrow())
            .low(rows.stream().mapToDouble(OHLCV::getLow).min().orElseThrow())
            .close(rows.get(rows.size()-1).getClose()).volume(rows.stream().mapToDouble(OHLCV::getVolume).sum()).build();
    }
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();var source=mapper.readTree(Files.readString(Path.of(args[1]))).get("candidates");
        var ctor=BacktestService.class.getConstructors()[0];Object service=ctor.newInstance(new Object[ctor.getParameterCount()]);
        var exit=BacktestService.class.getDeclaredMethod("simulateClassicExit",List.class,double.class,double.class,double.class,String.class,boolean.class,boolean.class);exit.setAccessible(true);
        var results=new ArrayList<Map<String,Object>>();
        for(String ticker:List.of("AMD","TSLA")) {
            var days=new TreeMap<LocalDate,List<OHLCV>>();
            for(var b:mapper.readTree(Files.readString(Path.of(args[0],ticker+"-2024-09-23-2026-07-17-1m.json")))) {
                var t=Instant.ofEpochMilli(b.get("t").asLong()).atZone(ET);
                if(t.toLocalDate().isAfter(LocalDate.of(2026,7,17)))throw new IllegalArgumentException("Validation dates forbidden");
                if(t.toLocalTime().isBefore(LocalTime.of(9,30)) || !t.toLocalTime().isBefore(LocalTime.of(16,0)))continue;
                days.computeIfAbsent(t.toLocalDate(),x->new ArrayList<>()).add(OHLCV.builder().timestamp(b.get("t").asLong())
                    .open(b.get("o").asDouble()).high(b.get("h").asDouble()).low(b.get("l").asDouble()).close(b.get("c").asDouble()).volume(b.get("v").asDouble()).build());
            }
            for(int delayMinutes:List.of(0,1)) {
                var trades=new ArrayList<Map<String,Object>>();var selectedDays=new HashSet<LocalDate>();var skips=new TreeMap<String,Integer>();int candidates=0;
                for(var row:source) {
                    if(!ticker.equals(row.get("ticker").asText()) || !row.get("factors").asText().startsWith("pdhpdl-breakout-retest-"))continue;
                    candidates++;var date=LocalDate.parse(row.get("date").asText());if(selectedDays.contains(date))continue;
                    long decision=row.get("decision_ms").asLong(),entryTime=decision+delayMinutes*60000L;
                    var bars=days.get(date);String direction=row.get("direction").asText();boolean bullish=direction.equals("long");
                    double stop=row.get("stop").asDouble(),originalTarget=row.get("target").asDouble();
                    boolean expired=bars.stream().filter(b->b.getTimestamp()>=decision && b.getTimestamp()<entryTime)
                        .anyMatch(b->bullish?(b.getLow()<=stop || b.getHigh()>=originalTarget):(b.getHigh()>=stop || b.getLow()<=originalTarget));
                    if(expired){skips.merge("invalidated_or_target_reached_before_manual_entry",1,Integer::sum);continue;}
                    var forward=bars.stream().filter(b->b.getTimestamp()>=entryTime && b.getTimestamp()<entryTime+30*60000L).toList();
                    if(forward.isEmpty() || forward.get(0).getTimestamp()!=entryTime){skips.merge("missing_entry",1,Integer::sum);continue;}
                    double open=forward.get(0).getOpen(),fill=open*(bullish?1.0005:.9995),sign=bullish?1:-1,risk=sign*(fill-stop);
                    if(risk<=0 || sign*(open-stop)<=0){skips.merge("invalid_stop",1,Integer::sum);continue;}
                    double target=fill+sign*2*risk;
                    var context=new ArrayList<OHLCV>();
                    var prior=days.lowerEntry(date);if(prior!=null)for(int i=0;i+4<prior.getValue().size();i+=5)context.add(aggregate(prior.getValue().subList(i,i+5)));
                    for(int i=0;i+4<bars.size() && bars.get(i+4).getTimestamp()+60000L<=decision;i+=5)context.add(aggregate(bars.subList(i,i+5)));
                    var setup=TradeSetup.builder().direction(direction).factorBreakdown(row.get("factors").asText()).build();
                    if(!PdhPdlDetector.targetHasRoom(context,setup,fill,target)){skips.merge("target_blocked_after_fill",1,Integer::sum);continue;}
                    Object outcome=exit.invoke(service,forward,fill,stop,target,direction,false,false);
                    double netPct=value(outcome,"pnlPct")-.05,netR=netPct/100*fill/risk;
                    trades.add(Map.of("date",date.toString(),"decision_ms",decision,"entry_ms",entryTime,"direction",direction,"entry",fill,"stop",stop,"target",target,"net_underlying_r",netR));selectedDays.add(date);
                }
                double gain=trades.stream().mapToDouble(t->(double)t.get("net_underlying_r")).filter(x->x>0).sum();
                double loss=-trades.stream().mapToDouble(t->(double)t.get("net_underlying_r")).filter(x->x<0).sum();
                double mean=trades.stream().mapToDouble(t->(double)t.get("net_underlying_r")).average().orElse(0);
                var result=new LinkedHashMap<String,Object>();result.put("ticker",ticker);result.put("manual_delay_minutes",delayMinutes);
                result.put("raw_candidates",candidates);result.put("trades",trades.size());result.put("wins",trades.stream().filter(t->(double)t.get("net_underlying_r")>0).count());
                result.put("mean_net_underlying_r",mean);result.put("profit_factor",loss>0?gain/loss:null);result.put("skips",skips);result.put("ledger",trades);results.add(result);
                var summary=new LinkedHashMap<>(result);summary.remove("ledger");System.out.println(mapper.writeValueAsString(summary));
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(),Map.of("scope","Development-only stock-price diagnostic, not options profitability", "target_r",2,"hold_minutes",30,"adverse_entry_bps",5,"exit_cost_bps_of_entry",5,"results",results));
    }
}
