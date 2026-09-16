import com.fasterxml.jackson.databind.*;
import com.smcscanner.model.*;
import com.smcscanner.strategy.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline signal audit, not a profit backtest. Uses only previously explored development dates. */
public class LevelLogicAudit {
    static final ZoneId ET=ZoneId.of("America/New_York");
    static OHLCV aggregate(List<OHLCV> rows) {
        return OHLCV.builder().timestamp(rows.get(0).getTimestamp()).open(rows.get(0).getOpen())
            .high(rows.stream().mapToDouble(OHLCV::getHigh).max().orElseThrow())
            .low(rows.stream().mapToDouble(OHLCV::getLow).min().orElseThrow())
            .close(rows.get(rows.size()-1).getClose()).volume(rows.stream().mapToDouble(OHLCV::getVolume).sum()).build();
    }
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();var out=new LinkedHashMap<String,Object>();var ledger=new ArrayList<Map<String,Object>>();
        out.put("scope","Every completed 5m detector decision on development dates; no execution or P&L claim");
        out.put("daily_context","Derived regular-session OHLC from complete cached minutes, not provider daily candles");
        var pdh=new PdhPdlDetector();var key=new KeyLevelStrategyDetector();
        for(String ticker:List.of("AMD","TSLA")) {
            var input=mapper.readTree(Files.readString(Path.of(args[0],ticker+"-2024-09-23-2026-07-17-1m.json")));
            var days=new TreeMap<LocalDate,List<OHLCV>>();
            for(var b:input) {
                var t=Instant.ofEpochMilli(b.get("t").asLong()).atZone(ET);
                if(t.toLocalDate().isAfter(LocalDate.of(2026,7,17)))throw new IllegalArgumentException("Validation dates forbidden");
                if(!t.toLocalTime().isBefore(LocalTime.of(9,30)) && t.toLocalTime().isBefore(LocalTime.of(16,0)))
                    days.computeIfAbsent(t.toLocalDate(),x->new ArrayList<>()).add(OHLCV.builder()
                        .timestamp(b.get("t").asLong()).open(b.get("o").asDouble()).high(b.get("h").asDouble())
                        .low(b.get("l").asDouble()).close(b.get("c").asDouble()).volume(b.get("v").asDouble()).build());
            }
            var daily=new ArrayList<OHLCV>();List<OHLCV> prior=List.of();int decisions=0;var counts=new TreeMap<String,Integer>();
            for(var day:days.entrySet()) {
                var rows=day.getValue();var session=new ArrayList<OHLCV>();
                long open=day.getKey().atTime(9,30).atZone(ET).toInstant().toEpochMilli();
                for(int i=0;i<rows.size();i++)if(rows.get(i).getTimestamp()!=open+i*60000L)throw new IllegalArgumentException("Missing minute");
                for(int i=0;i+4<rows.size();i+=5)session.add(aggregate(rows.subList(i,i+5)));
                for(int i=0;i<session.size();i++) {
                    var current=session.subList(0,i+1);var window=new ArrayList<>(prior);window.addAll(current);decisions++;
                    for(String detector:List.of("pdh-pdl","keylevel")) {
                        var setups=detector.equals("pdh-pdl")?pdh.detect(window,ticker,0,true):key.detect(current,daily,ticker,0,null,true);
                        for(var s:setups) {
                            counts.merge(detector,1,Integer::sum);var row=new LinkedHashMap<String,Object>();
                            row.put("ticker",ticker);row.put("detector",detector);row.put("date",day.getKey().toString());
                            row.put("decision_ms",session.get(i).getTimestamp()+300000);row.put("direction",s.getDirection());
                            row.put("entry",s.getEntry());row.put("stop",s.getStopLoss());row.put("target",s.getTakeProfit());
                            row.put("rr",s.rrRatio());row.put("factors",s.getFactorBreakdown());ledger.add(row);
                        }
                    }
                }
                if(rows.size()==390)daily.add(aggregate(rows));
                prior=session;
            }
            out.put(ticker,Map.of("sessions",days.size(),"decisions",decisions,"candidates",counts));
        }
        out.put("candidates",ledger);mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),out);
        out.remove("candidates");System.out.println(mapper.writeValueAsString(out));
    }
}
