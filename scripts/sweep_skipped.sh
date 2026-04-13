#!/bin/bash
# Full strategy × timeframe sweep for skipped tickers
# Tests all 8 intraday strategies at all 5 timeframes (HYBRID exits)
# Also tests SWING mode to find multi-day edges
#
# Usage:
#   bash scripts/sweep_skipped.sh              # all skipped tickers
#   bash scripts/sweep_skipped.sh GOOGL QQQ    # specific tickers

BASE="https://smc-scanner-java-v2-production.up.railway.app"
EXIT="HYBRID"

# Priority order: highest-quality tickers first
ALL_TICKERS="GOOGL QQQ PANW PYPL SQ SHOP RIVN MSTR UBER SNAP ABNB BA DIS GS NET NIO RIOT ROKU SMCI HOOD BABA"
TICKERS="${@:-$ALL_TICKERS}"

# All 8 intraday strategies
INTRADAY_STRATEGIES="smc vwap breakout keylevel vsqueeze vwap3d idiv gammapin"
# Timeframes
DAYS_LIST="30 60 90 180 365"
# Modes to test
INTRADAY_MODE="INTRADAY"
SWING_MODE="SWING"
SCALP_MODE="SCALP"

# Minimum thresholds to consider a result "has edge"
MIN_TRADES=5
MIN_WR=55
MIN_OPT_PNL=0   # must be at least breakeven on options P&L

py3() { python3 -c "$1" 2>/dev/null; }

call_api() {
  local URL="$1"
  curl -sf --max-time 150 "$URL" 2>/dev/null
}

extract() {
  local JSON="$1" KEY="$2"
  py3 "import sys,json; d=json.loads('''$JSON'''); print(d.get('$KEY',''))" 2>/dev/null
}

gt() {
  py3 "print(1 if float('${1:-0}') > float('${2:-0}') else 0)" 2>/dev/null
}

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  FULL STRATEGY SWEEP — SKIPPED TICKERS                      ║"
echo "║  Strategies: smc vwap breakout keylevel vsqueeze vwap3d      ║"
echo "║              idiv gammapin + scalp (scalp model)             ║"
echo "║  Timeframes: 30d 60d 90d 180d 365d                           ║"
echo "║  Exit: HYBRID (options P&L)                                  ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

SUMMARY_FILE="/tmp/sweep_skipped_summary_$$.txt"
echo "TICKER | MODEL | STRATEGY | DAYS | TRADES | WR% | OPT_PNL | VERDICT" > "$SUMMARY_FILE"

for TICKER in $TICKERS; do
  echo ""
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "  TICKER: $TICKER"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

  # ── INTRADAY MODEL ────────────────────────────────────────────────
  echo ""
  echo "  [INTRADAY] Testing all 8 strategies × 5 timeframes..."
  BEST_INTRADAY_PNL=-999999
  BEST_INTRADAY_LABEL=""
  BEST_INTRADAY_STRATEGY=""
  BEST_INTRADAY_DAYS=180

  for STRAT in $INTRADAY_STRATEGIES; do
    for DAYS in $DAYS_LIST; do
      URL="${BASE}/api/backtest?ticker=${TICKER}&days=${DAYS}&mode=${INTRADAY_MODE}&exitStyle=${EXIT}&strategy=${STRAT}"
      RESP=$(call_api "$URL")
      if [ -z "$RESP" ]; then
        echo "    [SKIP] $STRAT ${DAYS}d — no response"
        continue
      fi
      TRADES=$(py3 "import json; d=json.loads('''$RESP'''); print(d.get('total_trades',0))")
      WR=$(py3     "import json; d=json.loads('''$RESP'''); print(d.get('win_rate',0))")
      PNL=$(py3    "import json; d=json.loads('''$RESP'''); print(d.get('opt_total_pnl',0))")
      EXP=$(py3    "import json; d=json.loads('''$RESP'''); print(d.get('expectancy',0))")
      LABEL="    $STRAT ${DAYS}d → ${TRADES}T ${WR}%WR \$${PNL} exp=${EXP}"

      # Flag anything that meets minimum edge criteria
      PASSES_TRADES=$(py3 "print(1 if int('${TRADES:-0}') >= $MIN_TRADES else 0)")
      PASSES_WR=$(py3     "print(1 if float('${WR:-0}') >= $MIN_WR else 0)")
      PASSES_PNL=$(py3    "print(1 if float('${PNL:-0}') >= $MIN_OPT_PNL else 0)")
      if [ "$PASSES_TRADES" = "1" ] && [ "$PASSES_WR" = "1" ] && [ "$PASSES_PNL" = "1" ]; then
        echo "  ✓ $LABEL"
        echo "$TICKER | INTRADAY | $STRAT | ${DAYS}d | $TRADES | $WR | \$$PNL | EDGE" >> "$SUMMARY_FILE"
        # Track per-strategy timeframe pass count
        KEY="${TICKER}_INTRADAY_${STRAT}"
        eval "CNT_${KEY}=\$((${CNT_${KEY}:-0} + 1))"
      else
        echo "  · $LABEL"
      fi

      # Track best by opt P&L
      IS_BETTER=$(gt "$PNL" "$BEST_INTRADAY_PNL")
      if [ "$IS_BETTER" = "1" ]; then
        BEST_INTRADAY_PNL=$PNL
        BEST_INTRADAY_LABEL="$STRAT ${DAYS}d → ${TRADES}T ${WR}%WR \$${PNL}"
        BEST_INTRADAY_STRATEGY=$STRAT
        BEST_INTRADAY_DAYS=$DAYS
      fi
    done

    # Print per-strategy score after all timeframes tested
    KEY="${TICKER}_INTRADAY_${STRAT}"
    SCORE=$(eval "echo \${CNT_${KEY}:-0}")
    [ "$SCORE" -ge 1 ] && echo "    → $STRAT score: ${SCORE}/5 timeframes"
  done

  echo ""
  echo "  ★ BEST INTRADAY: $BEST_INTRADAY_LABEL"

  # ── SCALP MODEL ────────────────────────────────────────────────────
  echo ""
  echo "  [SCALP] Testing Bollinger scalp × 5 timeframes..."
  BEST_SCALP_PNL=-999999
  BEST_SCALP_LABEL=""

  for DAYS in $DAYS_LIST; do
    URL="${BASE}/api/backtest?ticker=${TICKER}&days=${DAYS}&mode=${SCALP_MODE}&exitStyle=${EXIT}&strategy=scalp"
    RESP=$(call_api "$URL")
    if [ -z "$RESP" ]; then
      echo "    [SKIP] scalp ${DAYS}d — no response"
      continue
    fi
    TRADES=$(py3 "import json; d=json.loads('''$RESP'''); print(d.get('total_trades',0))")
    WR=$(py3     "import json; d=json.loads('''$RESP'''); print(d.get('win_rate',0))")
    PNL=$(py3    "import json; d=json.loads('''$RESP'''); print(d.get('opt_total_pnl',0))")
    EXP=$(py3    "import json; d=json.loads('''$RESP'''); print(d.get('expectancy',0))")
    LABEL="    scalp ${DAYS}d → ${TRADES}T ${WR}%WR \$${PNL} exp=${EXP}"

    PASSES_TRADES=$(py3 "print(1 if int('${TRADES:-0}') >= $MIN_TRADES else 0)")
    PASSES_WR=$(py3     "print(1 if float('${WR:-0}') >= $MIN_WR else 0)")
    PASSES_PNL=$(py3    "print(1 if float('${PNL:-0}') >= $MIN_OPT_PNL else 0)")
    if [ "$PASSES_TRADES" = "1" ] && [ "$PASSES_WR" = "1" ] && [ "$PASSES_PNL" = "1" ]; then
      echo "  ✓ $LABEL"
      echo "$TICKER | SCALP | scalp | ${DAYS}d | $TRADES | $WR | \$$PNL | EDGE" >> "$SUMMARY_FILE"
    else
      echo "  · $LABEL"
    fi

    IS_BETTER=$(gt "$PNL" "$BEST_SCALP_PNL")
    if [ "$IS_BETTER" = "1" ]; then
      BEST_SCALP_PNL=$PNL
      BEST_SCALP_LABEL="scalp ${DAYS}d → ${TRADES}T ${WR}%WR \$${PNL}"
    fi
  done

  echo ""
  echo "  ★ BEST SCALP: $BEST_SCALP_LABEL"

  # ── SWING MODEL ────────────────────────────────────────────────────
  echo ""
  echo "  [SWING] Testing swing detector × 180d + 365d..."
  BEST_SWING_PNL=-999999
  BEST_SWING_LABEL=""

  for DAYS in 90 180 365; do
    URL="${BASE}/api/backtest?ticker=${TICKER}&days=${DAYS}&mode=${SWING_MODE}&exitStyle=${EXIT}"
    RESP=$(call_api "$URL")
    if [ -z "$RESP" ]; then
      echo "    [SKIP] swing ${DAYS}d — no response"
      continue
    fi
    TRADES=$(py3 "import json; d=json.loads('''$RESP'''); print(d.get('total_trades',0))")
    WR=$(py3     "import json; d=json.loads('''$RESP'''); print(d.get('win_rate',0))")
    PNL=$(py3    "import json; d=json.loads('''$RESP'''); print(d.get('opt_total_pnl',0))")
    EXP=$(py3    "import json; d=json.loads('''$RESP'''); print(d.get('expectancy',0))")
    LABEL="    swing ${DAYS}d → ${TRADES}T ${WR}%WR \$${PNL} exp=${EXP}"

    PASSES_TRADES=$(py3 "print(1 if int('${TRADES:-0}') >= 3 else 0)")  # swing needs fewer trades
    PASSES_WR=$(py3     "print(1 if float('${WR:-0}') >= 55 else 0)")
    if [ "$PASSES_TRADES" = "1" ] && [ "$PASSES_WR" = "1" ]; then
      echo "  ✓ $LABEL"
      echo "$TICKER | SWING | swing | ${DAYS}d | $TRADES | $WR | \$$PNL | EDGE" >> "$SUMMARY_FILE"
    else
      echo "  · $LABEL"
    fi

    IS_BETTER=$(gt "$PNL" "$BEST_SWING_PNL")
    if [ "$IS_BETTER" = "1" ]; then
      BEST_SWING_PNL=$PNL
      BEST_SWING_LABEL="swing ${DAYS}d → ${TRADES}T ${WR}%WR \$${PNL}"
    fi
  done

  echo ""
  echo "  ★ BEST SWING: $BEST_SWING_LABEL"
  echo ""

done

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  SWEEP COMPLETE — EDGES FOUND (✓ rows only)                 ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
grep "EDGE" "$SUMMARY_FILE" | column -t -s '|' 2>/dev/null || grep "EDGE" "$SUMMARY_FILE"
echo ""
echo "Full results saved to: $SUMMARY_FILE"
