#!/usr/bin/env python3
"""Train pattern/filter candidates, select once, then open the held-out period once."""

import argparse
import json
import pathlib
import sys
import urllib.parse
import urllib.request

PATTERNS = ("scalp", "sweep-flip", "choch-primary", "pdh-pdl")
FILTERS = ("", "spy", "15m", "volume", "regime", "time")


def fetch(base_url, ticker, start, end, pattern, active_filter):
    query = urllib.parse.urlencode({
        "ticker": ticker, "mode": "INTRADAY", "exitStyle": "CLASSIC",
        "start": start, "end": end, "pattern": pattern, "filters": active_filter,
    })
    with urllib.request.urlopen(f"{base_url.rstrip('/')}/api/backtest?{query}", timeout=600) as response:
        payload = json.load(response)
    if payload.get("error"):
        raise RuntimeError(f"{ticker}: {payload['error']}")
    return payload


def summarize(rows):
    multiples = [trade["risk_multiple"] for row in rows for trade in row["trades"]]
    wins = sum(value > 0 for value in multiples)
    gains = sum(value for value in multiples if value > 0)
    losses = -sum(value for value in multiples if value < 0)
    equity = peak = drawdown = 0.0
    for value in multiples:
        equity += value
        peak = max(peak, equity)
        drawdown = max(drawdown, peak - equity)
    return {
        "trades": len(multiples),
        "win_rate": round(100 * wins / len(multiples), 1) if multiples else 0,
        "mean_r": round(sum(multiples) / len(multiples), 4) if multiples else 0,
        "profit_factor": round(gains / losses, 3) if losses else (999999.0 if gains else 0),
        "max_drawdown_r": round(drawdown, 3),
        "rejections": {
            key: sum(row.get("research_rejections", {}).get(key, 0) for row in rows)
            for key in FILTERS if key
        },
    }


def run_period(base_url, tickers, start, end, pattern, active_filter):
    rows = []
    for ticker in tickers:
        print(f"{start}..{end} {pattern}/{active_filter or 'none'} {ticker}",
              file=sys.stderr, flush=True)
        rows.append(fetch(base_url, ticker, start, end, pattern, active_filter))
    return {"summary": summarize(rows), "tickers": rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--tickers", required=True, help="Comma-separated, fixed before training")
    parser.add_argument("--train-start", required=True)
    parser.add_argument("--train-end", required=True)
    parser.add_argument("--validation-start", required=True)
    parser.add_argument("--validation-end", required=True)
    parser.add_argument("--minimum-trades", type=int, default=30)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    tickers = tuple(value.strip().upper() for value in args.tickers.split(",") if value.strip())
    if not tickers:
        raise SystemExit("At least one ticker is required")
    if args.train_end >= args.validation_start:
        raise SystemExit("Training must end before validation begins")

    training = []
    for pattern in PATTERNS:
        for active_filter in FILTERS:
            result = run_period(args.base_url, tickers, args.train_start, args.train_end,
                                pattern, active_filter)
            training.append({"pattern": pattern, "filter": active_filter or "none", **result})

    eligible = [row for row in training if row["summary"]["trades"] >= args.minimum_trades]
    if not eligible:
        raise SystemExit("No training candidate reached the minimum trade count; validation was not opened")
    winner = max(eligible, key=lambda row: (row["summary"]["mean_r"],
                                            row["summary"]["profit_factor"],
                                            -row["summary"]["max_drawdown_r"]))

    # The held-out period is requested only after selection. It is never used to rank candidates.
    winning_filter = "" if winner["filter"] == "none" else winner["filter"]
    validation = run_period(args.base_url, tickers, args.validation_start, args.validation_end,
                            winner["pattern"], winning_filter)
    report = {
        "method": "one detector, one optional filter, one contract, 2R target; validation opened once",
        "tickers": tickers,
        "training_range": [args.train_start, args.train_end],
        "validation_range": [args.validation_start, args.validation_end],
        "training_candidates": training,
        "selected": {"pattern": winner["pattern"], "filter": winner["filter"],
                     "training": winner["summary"]},
        "validation": validation,
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, allow_nan=False))
    print(json.dumps({"selected": report["selected"],
                      "validation": validation["summary"], "output": str(output)}, indent=2))


if __name__ == "__main__":
    main()
