import com.fasterxml.jackson.databind.*;
import com.smcscanner.model.*;
import com.smcscanner.strategy.*;
import com.smcscanner.indicator.VolumeProfileCalculator;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline detector audit: every completed 5m decision, no first-trade-per-day selection. */
public class ScalpLogicAudit {
    static final ZoneId ET=ZoneId.of("America/New_York");
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();var out=new LinkedHashMap<String,Object>();var ledger=new ArrayList<Map<String,Object>>();
        var priorKeys=new HashSet<String>();
        if(args.length>2) for(var row:mapper.readTree(Files.readString(Path.of(args[2]))).get("candidates"))
            priorKeys.add(row.get("ticker").asText()+":"+row.get("decision_ms").asLong());
        var priorDecisions=new ArrayList<Map<String,Object>>();
        out.put("scope","All detector decisions on cached development bars, not an executed-trade backtest");
        for(String ticker:List.of("AMD","TSLA")) {
            var input=mapper.readTree(Files.readString(Path.of(args[0],ticker+"-2024-09-23-2026-07-17-1m.json")));
            var minutes=new TreeMap<String,List<JsonNode>>();
            for(var b:input) {
                var t=Instant.ofEpochMilli(b.get("t").asLong()).atZone(ET);
                if(t.toLocalDate().toString().compareTo("2026-07-17")>0)throw new IllegalArgumentException("Later dates forbidden");
                if(!t.toLocalTime().isBefore(LocalTime.of(9,30)) && t.toLocalTime().isBefore(LocalTime.of(16,0)))
                    minutes.computeIfAbsent(t.toLocalDate().toString(),x->new ArrayList<>()).add(b);
            }
            var detector=new ScalpMomentumDetector(null,new VolumeProfileCalculator(),null);
            var history=new ArrayList<OHLCV>();int decisions=0,accepted=0;var reasons=new TreeMap<String,Integer>();
            for(var day:minutes.entrySet()) {
                var rows=day.getValue();var session=new ArrayList<OHLCV>();
                for(int i=0;i+4<rows.size();i+=5) {
                    long ts=rows.get(i).get("t").asLong();double hi=0,lo=Double.MAX_VALUE,vol=0;
                    for(int j=0;j<5;j++) {
                        var b=rows.get(i+j);if(b.get("t").asLong()!=ts+j*60000L)throw new IllegalArgumentException("Missing minute");
                        hi=Math.max(hi,b.get("h").asDouble());lo=Math.min(lo,b.get("l").asDouble());vol+=b.get("v").asDouble();
                    }
                    session.add(OHLCV.builder().timestamp(ts).open(rows.get(i).get("o").asDouble()).high(hi).low(lo)
                            .close(rows.get(i+4).get("c").asDouble()).volume(vol).build());
                }
                for(int i=0;i<session.size();i++) {
                    var window=new ArrayList<>(history);window.addAll(session.subList(0,i+1));
                    var setups=detector.detect(window,List.of(),ticker,0,true);decisions++;
                    // New versions expose a reason. Old versions have only empty/non-empty output.
                    try {
                        var inspect=detector.getClass().getMethod("inspect",List.class,List.class,String.class,double.class,boolean.class);
                        Object detail=inspect.invoke(detector,window,List.of(),ticker,0,true);
                        String reason=(String)detail.getClass().getMethod("reason").invoke(detail);
                        reasons.merge(reason,1,Integer::sum);
                        long decision=session.get(i).getTimestamp()+300000;
                        if(priorKeys.contains(ticker+":"+decision)) priorDecisions.add(Map.of(
                                "ticker",ticker,"decision_ms",decision,"accepted",!setups.isEmpty(),"reason",reason));
                    } catch(NoSuchMethodException absent) { reasons.merge(setups.isEmpty()?"undisclosed rejection":"accepted",1,Integer::sum); }
                    for(var setup:setups) {
                        accepted++;var row=new LinkedHashMap<String,Object>();
                        row.put("ticker",ticker);row.put("date",day.getKey());row.put("decision_ms",session.get(i).getTimestamp()+300000);
                        row.put("direction",setup.getDirection());row.put("entry",setup.getEntry());row.put("stop",setup.getStopLoss());
                        row.put("target",setup.getTakeProfit());row.put("rr",setup.rrRatio());row.put("factors",setup.getFactorBreakdown());
                        ledger.add(row);
                    }
                }
                history.addAll(session);if(history.size()>200)history=new ArrayList<>(history.subList(history.size()-200,history.size()));
            }
            out.put(ticker,Map.of("decisions",decisions,"accepted",accepted,"reasons",reasons));
        }
        out.put("previous_candidate_decisions",priorDecisions);
        out.put("candidates",ledger);mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),out);
        out.remove("candidates");out.remove("previous_candidate_decisions");System.out.println(mapper.writeValueAsString(out));
    }
}
