"""Actual-contract, candle-reference diagnostic. Not quote-based execution or validation.

Frozen entry cohort, 15-minute underlying rule, 7-14 DTE nearest-strike contracts.
POLYGON_API_KEY stays in memory. All provider responses are cached without credentials.
"""
import argparse
from datetime import date, timedelta
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import urlencode

from audit_execution_costs import Provider


def reference_times(candidate):
    result = candidate["outcomes"]["15m:0bps"]
    entry = candidate["entry_ts"] + 60000
    # Touches are only known after the entire minute closes. Add a further minute
    # of manual reaction; timeout is already timestamped at its minute close.
    detected = result["exit_ts"] + (0 if result["exit_reason"] == "TIME" else 60000)
    if detected <= entry:
        return None
    return entry, detected + 60000


def choose_contract(rows, candidate):
    expiry_min = date.fromisoformat(candidate["date"]) + timedelta(days=7)
    expiry_max = date.fromisoformat(candidate["date"]) + timedelta(days=14)
    kind = "call" if candidate["direction"] == "long" else "put"
    valid = [r for r in rows if r.get("contract_type") == kind and r.get("shares_per_contract") == 100
             and not r.get("additional_underlyings")
             and expiry_min.isoformat() <= r["expiration_date"] <= expiry_max.isoformat()]
    # Closest eligible expiration, then closest strike. Ties use lower strike for
    # calls and higher strike for puts; no future volume/return sorting.
    return min(valid, key=lambda r: (r["expiration_date"], abs(r["strike_price"] - candidate["market_open"]),
                                    r["strike_price"] if kind == "call" else -r["strike_price"])) if valid else None


def price_reference(rows, decision_ts, entry_ts, exit_ts):
    by_ts = {r["t"]: r for r in rows}
    if not any(r["t"] + 60000 <= decision_ts and r.get("v", 0) > 0 for r in rows):
        return {"status": "no_trade_history_before_decision"}
    if entry_ts not in by_ts or exit_ts not in by_ts:
        return {"status": "missing_exact_entry_or_exit_minute"}
    enter, leave = by_ts[entry_ts], by_ts[exit_ts]
    if enter.get("v", 0) <= 0 or leave.get("v", 0) <= 0 or enter["o"] <= 0 or leave["o"] <= 0:
        return {"status": "invalid_reference_bar"}
    return {"status": "priced_reference", "entry_reference": enter["o"], "exit_reference": leave["o"]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", required=True)
    p.add_argument("--cache", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()
    source = Path(args.input)
    candidates = sorted(json.loads(source.read_text())["ledger"], key=lambda r: (r["entry_ts"], r["ticker"]))
    if any(r["date"] > "2026-07-17" for r in candidates):
        raise ValueError("Refusing later dates")
    provider = Provider(os.environ["POLYGON_API_KEY"])
    cache = Path(args.cache)
    cache.mkdir(parents=True, exist_ok=True)

    def cached_get(path):
        file = cache / (hashlib.sha256(path.encode()).hexdigest() + ".json")
        if file.exists():
            return json.loads(file.read_text())
        payload = provider.get("https://api.polygon.io" + path)
        if payload.get("status") in {"ERROR", "NOT_AUTHORIZED"} or payload.get("next_url"):
            raise ValueError("Incomplete or rejected provider response")
        file.write_text(json.dumps(payload))
        return payload

    report = {"stage": "development actual-contract candle-reference diagnostic; not executable P&L",
              "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
              "selection": "all frozen zone entries; 15m underlying exit; next 7-14 DTE expiration; nearest strike",
              "latency": "entry 1m after confirmation; touch exit 1m after touch bar closes; timeout exit 1m after timeout",
              "cost_scenarios": "0%, 2%, 5%, 10% of option reference premium round-trip, half per side; fees excluded",
              "limitations": ["Trade-bar opens are not bid/ask quotes or guaranteed fills",
                              "Missing exact bars are unpriceable; never carried forward",
                              "No fees, order-book depth, borrow, portfolio sizing or unused validation modeled",
                              "Tiny sample; no profitability inference"], "trades": []}
    for c in candidates:
        row = {k: c[k] for k in ["ticker", "date", "direction", "entry_ts", "stop", "target"]}
        times = reference_times(c)
        if times is None:
            row["status"] = "underlying_exited_before_manual_entry"
        else:
            start = date.fromisoformat(c["date"])
            query = urlencode({"underlying_ticker": c["ticker"], "as_of": c["date"],
                               "contract_type": "call" if c["direction"] == "long" else "put",
                               "expiration_date.gte": str(start + timedelta(days=7)),
                               "expiration_date.lte": str(start + timedelta(days=14)),
                               "strike_price.gte": c["market_open"] * .9,
                               "strike_price.lte": c["market_open"] * 1.1, "limit": 1000})
            try:
                contracts = cached_get("/v3/reference/options/contracts?" + query).get("results", [])
                contract = choose_contract(contracts, c)
                if contract is None:
                    row["status"] = "no_eligible_contract"
                else:
                    symbol = contract["ticker"]
                    row["contract"] = symbol
                    bars = cached_get(f"/v2/aggs/ticker/{symbol}/range/1/minute/{c['date']}/{c['date']}?adjusted=true&sort=asc&limit=50000").get("results", [])
                    row.update(price_reference(bars, c["entry_ts"], *times))
                    row["option_entry_ts"], row["option_exit_ts"] = times
                    if row["status"] == "priced_reference":
                        row["scenarios"] = {}
                        for cost in [0, 2, 5, 10]:
                            buy = row["entry_reference"] * (1 + cost / 200)
                            sell = row["exit_reference"] * (1 - cost / 200)
                            row["scenarios"][str(cost)] = {"return_pct": (sell / buy - 1) * 100,
                                                          "one_contract_pnl": 100 * (sell - buy)}
            except (RuntimeError, ValueError) as error:
                row["status"] = "data_access_failure"
                row["error"] = str(error)
        report["trades"].append(row)
        priced = [r for r in report["trades"] if r["status"] == "priced_reference"]
        report["summary"] = {"processed": len(report["trades"]), "cohort": len(candidates), "priced": len(priced)}
        report["summary"]["scenarios"] = {str(cost): {
            "mean_return_pct": sum(r["scenarios"][str(cost)]["return_pct"] for r in priced) / len(priced),
            "wins": sum(r["scenarios"][str(cost)]["return_pct"] > 0 for r in priced)}
            for cost in [0, 2, 5, 10]} if priced else {}
        Path(args.output).write_text(json.dumps(report, indent=2))
        print(c["ticker"], c["date"], row["status"], flush=True)
    print(json.dumps(report["summary"]), flush=True)


if __name__ == "__main__":
    main()
