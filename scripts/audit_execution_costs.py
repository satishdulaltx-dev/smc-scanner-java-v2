#!/usr/bin/env python3
"""Replay frozen development entries at declared costs using original minute bars.

Costs are scenarios, not measured spreads. No rule/entry selection is performed.
POLYGON_API_KEY is read only from the process environment and never persisted.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import random
import time
from collections import defaultdict
from datetime import datetime, time as daytime
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

ET = ZoneInfo("America/New_York")
DEVELOPMENT_END = "2026-07-17"
COSTS = (0, 2, 5, 10, 20)


class Provider:
    def __init__(self, key):
        self.key, self.last = key, 0.0

    def get(self, url):
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.hostname != "api.polygon.io":
            raise ValueError("Unexpected provider destination")
        for attempt in range(6):
            time.sleep(max(0, 12.5 - (time.monotonic() - self.last)))
            self.last = time.monotonic()
            try:
                with urlopen(Request(url, headers={"Authorization": "Bearer " + self.key}), timeout=30) as r:
                    return json.load(r)
            except HTTPError as e:
                if (e.code == 429 or e.code >= 500) and attempt < 5:
                    time.sleep(15 * (attempt + 1))
                    continue
                raise RuntimeError(f"Provider HTTP {e.code}") from None
        raise RuntimeError("Provider retries exhausted")

    def bars(self, ticker, start, end, cache):
        path = cache / f"{ticker}-{start}-{end}-1m.json"
        if path.exists():
            return json.loads(path.read_text())
        url = f"https://api.polygon.io/v2/aggs/ticker/{ticker}/range/1/minute/{start}/{end}?adjusted=true&sort=asc&limit=50000"
        rows, seen = [], set()
        while url:
            if url in seen or len(seen) > 100:
                raise ValueError("Incomplete provider pagination")
            seen.add(url)
            page = self.get(url)
            if page.get("status") == "ERROR":
                raise ValueError("Provider rejected bars")
            rows.extend(page.get("results", []))
            print(f"Loaded {ticker}: page {len(seen)}, {len(rows)} bars", flush=True)
            url = page.get("next_url")
        if not rows:
            raise ValueError("No historical bars")
        cache.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(rows, separators=(",", ":")))
        return rows


def session_bars(rows):
    days, last = defaultdict(dict), -1
    for b in rows:
        if b["t"] <= last:
            raise ValueError("Duplicate or unordered bar")
        last = b["t"]
        if not all(math.isfinite(b[k]) for k in ("o", "h", "l", "c", "v")):
            raise ValueError("Non-finite bar")
        if not (0 < b["l"] <= min(b["o"], b["c"]) <= max(b["o"], b["c"]) <= b["h"]):
            raise ValueError("Invalid OHLC")
        at = datetime.fromtimestamp(b["t"] / 1000, ET)
        if daytime(9, 30) <= at.time() < daytime(16):
            days[at.date().isoformat()][b["t"]] = b
    return days


def replay(candidate, bars, bps):
    """Fixed stop, target recalculated from each fill, stop-first on ambiguous bars."""
    sign = 1 if candidate["direction"] == "long" else -1
    fill = bars[0]["o"] * (1 + sign * bps / 20000)
    stop = candidate["sl"]
    if sign * (fill - stop) <= 0 or sign * (bars[0]["o"] - stop) <= 0:
        raise ValueError("Invalid initial stop for this scenario")
    risk = abs(fill - stop)
    target = fill + sign * candidate["target_r"] * risk
    reason, exit_price, exit_ts = "TIMEOUT", bars[-1]["c"], bars[-1]["t"]
    for bar in bars:
        if sign * (bar["o"] - stop) <= 0:
            reason, exit_price, exit_ts = "GAP_STOP", bar["o"], bar["t"]
            break
        stop_hit = bar["l"] <= stop if sign == 1 else bar["h"] >= stop
        target_hit = bar["h"] >= target if sign == 1 else bar["l"] <= target
        if stop_hit or target_hit:
            reason = "STOP" if stop_hit else "TARGET"
            exit_price, exit_ts = (stop if stop_hit else target), bar["t"]
            break
    # Match the declared production stress convention: half cost on entry,
    # half as an entry-notional exit charge. No intermediate rounding.
    pnl_fraction = sign * (exit_price - fill) / fill - bps / 20000
    return {"r": pnl_fraction * fill / risk, "pnl_pct": 100 * pnl_fraction,
            "exit_ts": exit_ts, "reason": reason}


def summary(rows):
    by_day, by_month = defaultdict(list), defaultdict(float)
    for row in rows:
        by_day[row["date"]].append(row["r"])
        by_month[row["date"][:7]] += row["r"]
    if not rows:
        return {"trades": 0}
    # Resample entire sessions, preserving all within-day stock correlations.
    blocks = list(by_day.values())
    rng, samples = random.Random(73019), []
    for _ in range(3000):
        picked = [blocks[rng.randrange(len(blocks))] for _ in blocks]
        samples.append(sum(map(sum, picked)) / sum(map(len, picked)))
    samples.sort()
    values = [r["r"] for r in rows]
    loss = -sum(x for x in values if x < 0)
    return {"trades": len(rows), "mean_r": sum(values) / len(values),
            "win_rate": sum(x > 0 for x in values) / len(values),
            "profit_factor": sum(x for x in values if x > 0) / loss if loss else None,
            "session_bootstrap_ci95": [samples[75], samples[2924]],
            "positive_months": sum(x > 0 for x in by_month.values()),
            "active_months": len(by_month)}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", action="append", required=True)
    p.add_argument("--cache", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()
    provider = Provider(os.environ["POLYGON_API_KEY"])
    report = {"validation_opened": False, "development_end": DEVELOPMENT_END,
              "costs_bps": COSTS, "costs_measured": False,
              "method": "Exact fixed-entry replay; per-scenario fills and targets; unrounded P&L; session bootstrap",
              "limitations": ["Frozen entries selected by the previous 10bps run; not a new strategy validation",
                              "OHLC cannot resolve intrabar ordering; stop-first retained",
                              "Confidence intervals do not correct the full historical strategy search"],
              "results": {}}
    try:
        probe = provider.get("https://api.polygon.io/v3/quotes/AMD?timestamp=2026-06-01&limit=1&order=asc")
        report["quote_access_probe"] = {"available": bool(probe.get("results")), "date": "2026-06-01"}
    except RuntimeError as error:
        report["quote_access_probe"] = {"available": False, "error": str(error)}
    print("Quote access:", report["quote_access_probe"], flush=True)
    for source in args.input:
        path = Path(source)
        payload = json.loads(path.read_text())
        payload = payload.get("result", payload)
        if payload.get("end_date", "9999") > DEVELOPMENT_END:
            raise ValueError("Refusing reserved validation dates")
        selected = [c for c in payload["candidate_ledger"] if c.get("selected")]
        days = session_bars(provider.bars(payload["ticker"], payload["start_date"], payload["end_date"], Path(args.cache)))
        variants, excluded, delta = {bps: [] for bps in COSTS}, [], []
        for c in selected:
            entry = c["entry_ts"]
            at = datetime.fromtimestamp(entry / 1000, ET)
            date = at.date().isoformat()
            close = int(datetime.combine(at.date(), daytime(16), ET).timestamp() * 1000)
            end = min(entry + c["max_hold_minutes"] * 60000, close)
            times = range(entry, end, 60000)
            if any(t not in days.get(date, {}) for t in times):
                excluded.append({"entry_ts": entry, "reason": "missing minute in requested hold"})
                continue
            bars = [days[date][t] for t in times]
            if abs(bars[0]["o"] - c["market_open"]) > 1e-6:
                raise ValueError("Historical provider revision changed entry price; stop comparison")
            try:
                outcomes = {bps: replay(c, bars, bps) for bps in COSTS}
            except ValueError:
                excluded.append({"entry_ts": entry, "reason": "stop invalid in one or more scenarios"})
                continue
            for bps, outcome in outcomes.items():
                variants[bps].append({"date": date, "entry_ts": entry, **outcome})
            legacy = c["exits"]["FIXED_R"]
            delta.append({"r_difference": outcomes[10]["r"] - legacy["risk_multiple"],
                          "same_exit_minute": outcomes[10]["exit_ts"] == legacy["exit_ts"]})
        key = payload["ticker"] + ":" + payload["pattern"]
        result = {"source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                  "selected": len(selected), "excluded": excluded,
                  "scenarios": {str(b): summary(rows) for b, rows in variants.items()},
                  "legacy_comparison": {"matching_exit_minutes": sum(d["same_exit_minute"] for d in delta),
                                        "compared": len(delta),
                                        "mean_r_difference": sum(d["r_difference"] for d in delta) / len(delta) if delta else None}}
        report["results"][key] = result
        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, indent=2))
        print(key, json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
