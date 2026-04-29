#!/bin/bash
# Bollinger Scalp parameter sweep — calls Railway /api/backtest directly
# Usage: bash scripts/sweep_scalp.sh [TICKER]
# Outputs: best params per ticker sorted by opt_total_pnl descending

BASE="https://smc-scanner-java-v2-production.up.railway.app"
DAYS=180
MODE="SCALP"
EXIT="HYBRID"
STRATEGY="scalp"

TICKERS="${1:-COIN AMD TSLA CRWD SOFI SPY NVDA META MARA AAPL SMCI PLTR RIOT MSFT NFLX DDOG AMZN}"

for TICKER in $TICKERS; do
  echo ""
  echo "════════════════════════════════════════════════"
  echo "  SWEEPING $TICKER  ($DAYS d, $EXIT)"
  echo "════════════════════════════════════════════════"

  BEST_PNL=-999999
  BEST_LABEL=""
  BEST_JSON=""
  COUNT=0

  for MC in 68 74 80 86; do
    for SL in 0.3 0.4 0.5 0.6; do
      for TP in 0.75 1.0 1.25; do
        COUNT=$((COUNT+1))
        URL="${BASE}/api/backtest?ticker=${TICKER}&days=${DAYS}&mode=${MODE}&exitStyle=${EXIT}&strategy=${STRATEGY}&mc=${MC}&sl=${SL}&tp=${TP}"
        RESP=$(curl -sf --max-time 120 "$URL" 2>/dev/null)
        if [ -z "$RESP" ]; then
          echo "  [SKIP] mc=$MC sl=$SL tp=$TP — no response"
          continue
        fi
        OPT_PNL=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('opt_total_pnl',0))" 2>/dev/null)
        WR=$(echo "$RESP"      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('win_rate',0))" 2>/dev/null)
        TRADES=$(echo "$RESP"  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('total_trades',0))" 2>/dev/null)
        EXP=$(echo "$RESP"     | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('expectancy',0))" 2>/dev/null)
        LABEL="mc=$MC sl=$SL tp=$TP → pnl=\$$OPT_PNL wr=${WR}% trades=$TRADES exp=$EXP"
        echo "  $LABEL"

        CMP=$(python3 -c "print(1 if float('${OPT_PNL:-0}') > float('${BEST_PNL:-0}') else 0)" 2>/dev/null)
        if [ "$CMP" = "1" ]; then
          BEST_PNL=$OPT_PNL
          BEST_LABEL=$LABEL
          BEST_JSON="$RESP"
        fi
      done
    done
  done

  echo ""
  echo "  ★ BEST for $TICKER: $BEST_LABEL"
done

echo ""
echo "Sweep complete."
