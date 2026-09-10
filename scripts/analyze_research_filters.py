#!/usr/bin/env python3
"""Compare each recorded research filter on one shared, development-only candidate set."""

import argparse
import json
import math
import pathlib
from collections import defaultdict
from datetime import datetime
from statistics import NormalDist
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
FILTERS = ["spy", "15m", "volume", "regime", "time", "cost"]


def unwrap(path):
    payload = json.loads(path.read_text())
    if payload.get("status") == "complete" and isinstance(payload.get("result"), dict):
        payload = payload["result"]
    if payload.get("error"):
        raise ValueError(f"{path.name}: {payload['error']}")
    if "candidate_ledger" not in payload:
        raise ValueError(f"{path.name}: no candidate ledger")
    return payload


def approximate_raw_r(candidate, net_r):
    fill = float(candidate["entry"])
    market = float(candidate["market_open"])
    stop = float(candidate["sl"])
    risk_at_fill = abs(fill - stop)
    net_pct = net_r * risk_at_fill / fill * 100.0
    gross_pct = net_pct + float(candidate["round_trip_cost_bps"]) / 200.0
    exit_price = fill * (1.0 + gross_pct / 100.0) if candidate["direction"] == "long" \
        else fill * (1.0 - gross_pct / 100.0)
    raw_risk = abs(market - stop)
    if raw_risk <= 0:
        return None
    return (exit_price - market) / raw_risk if candidate["direction"] == "long" \
        else (market - exit_price) / raw_risk


def row(candidate, ticker):
    outcome = candidate["exits"]["FIXED_R"]
    net_r = float(outcome["risk_multiple"])
    at = datetime.fromtimestamp(int(candidate["entry_ts"]) / 1000, ET)
    return {"ticker": ticker, "net_r": net_r, "raw_r": approximate_raw_r(candidate, net_r),
            "entry_ts": int(candidate["entry_ts"]), "exit_ts": int(outcome["exit_ts"]),
            "session": at.date().isoformat(), "month": at.strftime("%Y-%m")}


def select(candidates, ticker, pattern, filter_name):
    selected = []
    selected_dates = set()
    next_entry = -1
    for candidate in sorted(candidates, key=lambda item: item["entry_ts"]):
        if filter_name is None:
            if not candidate.get("selected", False):
                continue
        elif not candidate["gate_pass"].get(filter_name, False):
            continue
        chosen = row(candidate, ticker)
        if pattern != "ict-sweep-fvg-1m":
            session = (ticker, chosen["session"])
            if session in selected_dates:
                continue
            selected_dates.add(session)
        elif int(candidate["entry_ts"]) < next_entry:
            continue
        selected.append(chosen)
        next_entry = chosen["exit_ts"] + 60_000
    return selected


def summarize(rows, z_value):
    if not rows:
        return {"trades": 0}
    values = [item["net_r"] for item in rows]
    raw = [item["raw_r"] for item in rows if item["raw_r"] is not None]
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / max(1, len(values) - 1)
    margin = z_value * math.sqrt(variance / len(values))
    gains = sum(value for value in values if value > 0)
    losses = -sum(value for value in values if value < 0)
    months = defaultdict(float)
    for item in rows:
        months[item["month"]] += item["net_r"]
    return {"trades": len(values), "mean_net_r": mean,
            "mean_raw_r_approx": sum(raw) / len(raw) if raw else None,
            "total_net_r": sum(values), "profit_factor": gains / losses if losses else None,
            "multiplicity_adjusted_ci": [mean - margin, mean + margin],
            "positive_months": sum(value > 0 for value in months.values()),
            "active_months": len(months)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True)
    parser.add_argument("--development-end", default="2026-07-17")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    payloads = [unwrap(path) for path in sorted(pathlib.Path(args.input_dir).glob("*.json"))
                if path.name != pathlib.Path(args.output).name]
    if not payloads:
        raise ValueError("No experiment results found")
    for payload in payloads:
        if payload.get("end_date", "9999-12-31") > args.development_end:
            raise ValueError("Input opens data after the declared development end")
        if payload.get("filters"):
            raise ValueError("Use ungated runs so filters share the same candidate set")
        if any(set(candidate.get("gate_pass", {})) != set(FILTERS)
               for candidate in payload["candidate_ledger"]):
            raise ValueError("Candidate ledger does not contain every independent filter result")

    comparisons = [None] + FILTERS
    z_value = NormalDist().inv_cdf(1.0 - 0.05 / (2.0 * len(comparisons)))
    report = {"development_end": args.development_end, "validation_opened": False,
              "multiple_comparison_tests": len(comparisons), "results": {}}
    for filter_name in comparisons:
        rows = []
        by_ticker = {}
        for payload in payloads:
            chosen = select(payload["candidate_ledger"], payload["ticker"],
                            payload.get("pattern"), filter_name)
            rows.extend(chosen)
            by_ticker[payload["ticker"]] = summarize(chosen, z_value)
        report["results"][filter_name or "none"] = {
            "aggregate": summarize(rows, z_value), "by_ticker": by_ticker}
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps({name: result["aggregate"] for name, result in report["results"].items()}, indent=2))


if __name__ == "__main__":
    main()
