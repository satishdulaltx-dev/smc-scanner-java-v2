#!/usr/bin/env python3
"""Diagnostic training study. Fetch candidates once; never request a holdout."""
import argparse
import json
import pathlib
from datetime import datetime
from zoneinfo import ZoneInfo
from run_backtest_experiments import PATTERNS, FILTERS, fetch, summarize

ET = ZoneInfo("America/New_York")


def executable(candidate):
    # Older ledgers stored fill but not the original open. Reconstruct the
    # deterministic 5bps entry adjustment; never use the outcome to reject a row.
    long = candidate["direction"] == "long"
    market_open = candidate.get("market_open", candidate["entry"] / (1.0005 if long else 0.9995))
    return (min(market_open, candidate["entry"]) > candidate["sl"] if long
            else max(market_open, candidate["entry"]) < candidate["sl"])


def compare(rows):
    results = []
    for exit_style in ("FIXED_R", "CLASSIC", "HYBRID"):
        for gate in FILTERS:
            for selection in ("fixed_first_candidate", "first_passing_candidate"):
                selected = []
                for row in rows:
                    seen = set()
                    for candidate in sorted(row["candidate_ledger"], key=lambda c: c["entry_ts"]):
                        if not executable(candidate):
                            continue
                        day = datetime.fromtimestamp(candidate["entry_ts"] / 1000, ET).date()
                        if day in seen:
                            continue
                        if selection == "fixed_first_candidate":
                            seen.add(day)
                        if gate and not candidate["gate_pass"][gate]:
                            continue
                        seen.add(day)
                        selected.append({"entry_ts": candidate["entry_ts"], **candidate["exits"][exit_style]})
                results.append({"exit_style": exit_style, "filter": gate or "none",
                                "selection": selection, **summarize([{"trades": selected}])})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--tickers", required=True)
    parser.add_argument("--start", required=True)
    parser.add_argument("--end", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    report = {"period": [args.start, args.end], "purpose": "diagnosis on already-used training data",
              "risk": "one initial-risk unit per selected trade; no options sizing",
              "exit_policies": {"FIXED_R": "fixed stop, 2R target", "CLASSIC": "breakeven at 1R, 2R target",
                                "HYBRID": "2R-capped hybrid; 2.5R trailing is unreachable, so this is a redundancy check, not a trailing experiment"},
              "limitations": ["detector-internal gates remain embedded", "realized exit drawdown, no capital limits",
                               "one first detector signal per decision time; overlapping candidates are not independent"],
              "patterns": {}}
    for pattern in PATTERNS:
        rows = []
        for ticker in args.tickers.split(","):
            ticker = ticker.strip().upper()
            path = output / f"{pattern}-{ticker}.json"
            try:
                row = fetch(args.base_url, ticker, args.start, args.end, pattern, "", "FIXED_R")
                path.write_text(json.dumps(row))
                if "candidate_ledger" not in row:
                    raise ValueError("Server does not provide repaired candidate ledger")
                rows.append(row)
                print(f"{pattern} {ticker}: {len(row['candidate_ledger'])} candidates", flush=True)
            except Exception as error:
                # Persist every failed symbol; never silently select a smaller universe.
                report["patterns"][pattern] = {"incomplete": True, "failed_ticker": ticker, "error": str(error)}
                (output / "comparison.json").write_text(json.dumps(report, indent=2))
                raise
        report["patterns"][pattern] = {"tickers": [r["ticker"] for r in rows],
                                         "comparison": compare(rows)}
        (output / "comparison.json").write_text(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
