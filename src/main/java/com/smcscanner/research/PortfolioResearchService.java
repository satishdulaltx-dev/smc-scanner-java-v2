package com.smcscanner.research;

import com.smcscanner.backtest.BacktestExitStyle;
import com.smcscanner.backtest.BacktestMode;
import com.smcscanner.backtest.BacktestRun;
import com.smcscanner.backtest.BacktestService;
import com.smcscanner.backtest.ResearchStatistics;
import com.smcscanner.data.PolygonClient;
import com.smcscanner.model.OHLCV;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Research-only portfolio replay. It never writes live scanner or broker state. */
@Service
public class PortfolioResearchService {
    public static final String PATTERN="qualified-retest";
    private final BacktestService backtestService;
    private final PolygonClient polygon;
    private final StockInPlayRanker ranker=new StockInPlayRanker();

    public PortfolioResearchService(BacktestService backtestService,PolygonClient polygon) {
        this.backtestService=backtestService;this.polygon=polygon;
    }

    public Map<String,Object> run(List<String> requestedTickers,LocalDate start,LocalDate end,int topK) {
        List<String> tickers=normalizeTickers(requestedTickers);
        if (tickers.size()<StockInPlayRanker.MIN_UNIVERSE_SIZE)
            throw new IllegalArgumentException("Portfolio research requires at least five distinct tickers");
        if (tickers.size()>30) throw new IllegalArgumentException("Portfolio research supports at most 30 tickers per run");
        if (topK<1 || topK>Math.min(5,tickers.size()))
            throw new IllegalArgumentException("Top selection must be between 1 and 5 tickers");
        new BacktestRun(start,end,PATTERN,Set.of(),30,2.0);

        List<BacktestService.TradeResult> rawTrades=new ArrayList<>();
        List<String> warnings=new ArrayList<>();
        List<String> usableTickers=new ArrayList<>();
        for (String ticker:tickers) {
            BacktestService.BacktestResult result=backtestService.run(ticker,BacktestMode.INTRADAY,null,
                    BacktestExitStyle.FIXED_R,new BacktestRun(start,end,PATTERN,Set.of(),30,2.0));
            if (result.error!=null) {
                warnings.add(ticker+": "+result.error);
                continue;
            }
            usableTickers.add(ticker);
            rawTrades.addAll(result.trades);
            result.warnings.forEach(warning->{if(!warnings.contains(warning))warnings.add(warning);});
        }
        if (usableTickers.size()<StockInPlayRanker.MIN_UNIVERSE_SIZE)
            return failedResponse(tickers,usableTickers,start,end,topK,warnings,
                    "Fewer than five tickers had complete historical coverage");

        Map<String,List<OHLCV>> fiveMinute=new LinkedHashMap<>();
        Map<String,List<OHLCV>> daily=new LinkedHashMap<>();
        polygon.beginHistoricalSession();
        try {
            for (String ticker:usableTickers) {
                fiveMinute.put(ticker,polygon.getHistoricalBars(ticker,"5m",start.minusDays(30),end));
                daily.put(ticker,polygon.getHistoricalBars(ticker,"1d",start.minusDays(450),end));
            }
        } finally { polygon.endHistoricalSession(); }

        Map<Long,List<BacktestService.TradeResult>> tradesByDecision=new TreeMap<>();
        for (var trade:rawTrades) tradesByDecision.computeIfAbsent(trade.entryEpochMs(),ignored->new ArrayList<>()).add(trade);
        List<BacktestService.TradeResult> selected=new ArrayList<>();
        List<Map<String,Object>> rankAudit=new ArrayList<>();
        for (var decision:tradesByDecision.entrySet()) {
            long asOf=decision.getKey();
            List<StockInPlayRanker.Snapshot> snapshots=usableTickers.stream().map(ticker->
                    StockInPlaySnapshotBuilder.build(ticker,asOf,fiveMinute.get(ticker),daily.get(ticker),false)).toList();
            Map<String,StockInPlayRanker.RankedStock> rankedByTicker=new LinkedHashMap<>();
            for (var ranked:ranker.rank(snapshots,topK)) rankedByTicker.put(ranked.ticker(),ranked);
            for (var trade:decision.getValue()) {
                var rank=rankedByTicker.get(trade.ticker());
                boolean accepted=rank!=null&&rank.selected();
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("ticker",trade.ticker());row.put("entry_ts",asOf);row.put("accepted",accepted);
                if (rank!=null) {
                    row.put("rank",rank.rank());row.put("score",rank.score());
                    row.put("gap_percentile",rank.gapPercentile());
                    row.put("opening_rvol_percentile",rank.openingRvolPercentile());
                    row.put("opening_range_percentile",rank.openingRangePercentile());
                    row.put("dollar_volume_percentile",rank.dollarVolumePercentile());
                    row.put("eligible_universe",rank.universeSize());
                } else row.put("reason","incomplete synchronized snapshot");
                rankAudit.add(Map.copyOf(row));
                if (accepted) selected.add(trade);
            }
        }
        selected.sort(Comparator.comparingLong(BacktestService.TradeResult::entryEpochMs)
                .thenComparing(BacktestService.TradeResult::ticker));
        return response(tickers,usableTickers,start,end,topK,rawTrades,selected,rankAudit,warnings,null);
    }

    private Map<String,Object> response(List<String> requested,List<String> usable,LocalDate start,LocalDate end,
                                        int topK,List<BacktestService.TradeResult> raw,
                                        List<BacktestService.TradeResult> selected,List<Map<String,Object>> audit,
                                        List<String> warnings,String error) {
        ResearchStatistics.Summary evidence=ResearchStatistics.summarize(selected);
        int wins=(int)selected.stream().filter(t->t.riskMultiple()>0).count();
        int losses=(int)selected.stream().filter(t->t.riskMultiple()<0).count();
        int timeouts=(int)selected.stream().filter(t->"TIMEOUT".equals(t.outcome())).count();
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("ticker","STOCK-IN-PLAY PORTFOLIO");out.put("portfolio",true);out.put("research",true);
        out.put("pattern",PATTERN+" + synchronized universe rank");out.put("decision_timeframe","5m");
        out.put("requested_tickers",requested);out.put("tickers",usable);out.put("universe_size",usable.size());
        out.put("top_k",topK);out.put("requested_start_date",start.toString());out.put("start_date",start.toString());
        out.put("end_date",end.toString());out.put("lookback_days",ChronoUnit.DAYS.between(start,end)+1);
        out.put("exit_style",BacktestExitStyle.FIXED_R.name());out.put("max_hold_minutes",30);out.put("target_r",2.0);
        out.put("round_trip_cost_bps",BacktestRun.RESEARCH_ROUND_TRIP_COST_BPS);out.put("filters","stock-in-play rank");
        out.put("raw_total_trades",raw.size());out.put("rank_rejected",raw.size()-selected.size());
        out.put("rank_audit",audit);out.put("warnings",warnings);out.put("coverage",Map.of("usable_tickers",usable.size()));
        out.put("research_verdict",evidence.verdict());out.put("research_explanation",evidence.explanation());
        out.put("mean_r",evidence.meanR());out.put("total_r",evidence.totalR());out.put("profit_factor",evidence.profitFactor());
        out.put("mean_r_ci_low",evidence.ciLowR());out.put("mean_r_ci_high",evidence.ciHighR());
        out.put("positive_months",evidence.positiveMonths());out.put("active_months",evidence.activeMonths());
        out.put("total_trades",selected.size());out.put("wins",wins);out.put("losses",losses);
        out.put("be_stops",selected.size()-wins-losses);out.put("timeouts",timeouts);
        out.put("win_rate",selected.isEmpty()?0:Math.round(wins*1000.0/selected.size())/10.0);
        out.put("avg_win_pct",selected.stream().filter(t->t.pnlPct()>0).mapToDouble(BacktestService.TradeResult::pnlPct).average().orElse(0));
        out.put("avg_loss_pct",selected.stream().filter(t->t.pnlPct()<0).mapToDouble(t->Math.abs(t.pnlPct())).average().orElse(0));
        out.put("expectancy",selected.stream().mapToDouble(BacktestService.TradeResult::pnlPct).average().orElse(0));
        out.put("filtered_total",raw.size()-selected.size());out.put("filtered_by_reason",Map.of("outside_stock_in_play_rank",raw.size()-selected.size()));
        out.put("research_rejections",Map.of());out.put("candidate_ledger",List.of());
        out.put("news_filtered",0);out.put("ctx_filtered",0);out.put("quality_filtered",raw.size()-selected.size());
        out.put("execution_costs_measured",false);out.put("disabled",false);
        out.put("opt_total_pnl",0);out.put("opt_avg_win_pnl",0);out.put("opt_avg_loss_pnl",0);
        out.put("opt_expectancy",0);out.put("opt_total_return",0);out.put("avg_contracts",0);
        for(String bucket:List.of("bucket_85plus_total","bucket_85plus_wins","bucket_85plus_wr","bucket_85plus_exp",
                "bucket_75to84_total","bucket_75to84_wins","bucket_75to84_wr","bucket_75to84_exp",
                "bucket_below75_total","bucket_below75_wins","bucket_below75_wr","bucket_below75_exp"))out.put(bucket,0);
        out.put("trades",selected.stream().map(this::tradeMap).toList());
        if(error!=null)out.put("error",error);
        return out;
    }

    private Map<String,Object> tradeMap(BacktestService.TradeResult trade) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("ticker",trade.ticker());row.put("entry_time",trade.entryTime());row.put("exit_time",trade.exitTime());
        row.put("entry_ts",trade.entryEpochMs());row.put("exit_ts",trade.exitEpochMs());row.put("dir",trade.direction());
        row.put("strategy",trade.strategy());row.put("entry",trade.entry());row.put("sl",trade.sl());row.put("tp",trade.tp());
        row.put("outcome",trade.outcome());row.put("pnl_pct",trade.pnlPct());
        row.put("risk_multiple",Math.round(trade.riskMultiple()*1000.0)/1000.0);
        row.put("pattern",trade.pattern());row.put("confidence",trade.confidence());row.put("atr",trade.atr());
        if(trade.factorBreakdown()!=null)row.put("factor_breakdown",trade.factorBreakdown());
        return Map.copyOf(row);
    }

    private Map<String,Object> failedResponse(List<String> requested,List<String> usable,LocalDate start,LocalDate end,
                                              int topK,List<String> warnings,String error) {
        return response(requested,usable,start,end,topK,List.of(),List.of(),List.of(),warnings,error);
    }

    private List<String> normalizeTickers(List<String> values) {
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        if(values!=null)for(String value:values)if(value!=null&&!value.isBlank())unique.add(value.trim().toUpperCase());
        return List.copyOf(unique);
    }
}
