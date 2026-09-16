"""Frozen historical-zone experiment, offline, development data only.

Requires exchange_calendars. Inputs are credential-free Polygon minute-bar caches.
Does not place orders, tune parameters, or estimate options profits.
"""
import argparse
from collections import Counter
from datetime import datetime, time
import hashlib
import json
from pathlib import Path
from statistics import mean
from zoneinfo import ZoneInfo

from audit_execution_costs import session_bars, summary

ET = ZoneInfo("America/New_York")
DEVELOPMENT_END = "2026-07-17"


def market_schedule(start, end):
    import exchange_calendars as xc
    schedule = xc.get_calendar("XNYS").schedule.loc[start:end]
    # exchange_calendars 4.5.6 predates this one-off closure.
    # https://nasdaqtrader.com/TraderNews.aspx?id=ECA2024-632
    return [(str(day.date()), int(row["open"].timestamp() * 1000),
             int(row["close"].timestamp() * 1000))
            for day, row in schedule.iterrows() if str(day.date()) != "2025-01-09"]


def aggregate(bars, interval):
    out = []
    for i in range(0, len(bars) - interval + 1, interval):
        group = bars[i:i + interval]
        if any(b["t"] != group[0]["t"] + k * 60000 for k, b in enumerate(group)):
            raise ValueError("Incomplete aggregation interval")
        out.append({"t": group[0]["t"], "o": group[0]["o"], "c": group[-1]["c"],
                    "h": max(b["h"] for b in group), "l": min(b["l"] for b in group),
                    "v": sum(b["v"] for b in group)})
    return out


def historical_zones(prior_sessions):
    """Only completed prior sessions enter this function; confirm pivots two bars later."""
    pivots, ranges = [], []
    previous_close = None
    for date, bars in prior_sessions:
        fifteen = aggregate(bars, 15)
        for b in fifteen:
            ranges.append(max(b["h"] - b["l"], abs(b["h"] - previous_close),
                              abs(b["l"] - previous_close)) if previous_close else b["h"] - b["l"])
            previous_close = b["c"]
        for i in range(2, len(fifteen) - 2):
            b = fifteen[i]
            neighbors = fifteen[i-2:i] + fifteen[i+1:i+3]
            if b["h"] > max(x["h"] for x in neighbors):
                pivots.append((b["h"], date, fifteen[i+2]["t"] + 900000))
            if b["l"] < min(x["l"] for x in neighbors):
                pivots.append((b["l"], date, fifteen[i+2]["t"] + 900000))
    atr = mean(ranges[-14:]) if len(ranges) >= 14 else 0
    if atr <= 0:
        return [], 0
    groups = []
    for pivot in sorted(pivots):
        if not groups or pivot[0] - groups[-1][0][0] > .25 * atr:
            groups.append([])
        groups[-1].append(pivot)
    zones = [{"low": min(p[0] for p in g) - .1 * atr,
              "high": max(p[0] for p in g) + .1 * atr,
              "sessions": sorted({p[1] for p in g}),
              "confirmed_at": max(p[2] for p in g)}
             for g in groups if len({p[1] for p in g}) >= 2]
    return zones, atr


def first_candidate(bars, zones, atr, rejected):
    five = aggregate(bars, 5)
    active = []
    for i in range(6, len(five)):
        b = five[i]
        decision = b["t"] + 300000
        local = datetime.fromtimestamp(decision / 1000, ET).time()
        if local > time(14, 30):
            break
        if any(z["confirmed_at"] >= five[0]["t"] for z in zones):
            raise ValueError("Zone was not confirmed before this session")
        body = abs(b["c"] - b["o"]) / max(b["h"] - b["l"], 1e-12)
        next_active = []
        for state in active:
            z, sign = state["zone"], state["sign"]
            if i - state["index"] > 6:
                rejected["retest_expired"] += 1
                continue
            if (sign == 1 and b["c"] < z["low"] - .1 * atr
                    or sign == -1 and b["c"] > z["high"] + .1 * atr):
                rejected["breakout_failed_before_entry"] += 1
                continue
            confirms = (b["l"] <= z["high"] + .1 * atr and b["c"] > z["high"]
                        and b["c"] > b["o"]) if sign == 1 else (
                        b["h"] >= z["low"] - .1 * atr and b["c"] < z["low"] and b["c"] < b["o"])
            if not confirms or body < .5 or local < time(10):
                next_active.append(state)
                continue
            at = (i + 1) * 5
            if at >= len(bars):
                continue
            market_open = bars[at]["o"]
            fill = market_open * (1 + sign * .0005)
            stop = min(z["low"], b["l"]) - .1 * atr if sign == 1 else max(z["high"], b["h"]) + .1 * atr
            targets = [other["low"] for other in zones if other["low"] > fill] if sign == 1 else [
                other["high"] for other in zones if other["high"] < fill]
            if not targets:
                rejected["no_next_historical_zone"] += 1
                continue
            target = min(targets) if sign == 1 else max(targets)
            risk = sign * (fill - stop)
            if sign * (market_open - stop) <= 0 or risk <= 0 or sign * (target - fill) / risk < 1.5:
                rejected["insufficient_room_or_invalid_stop"] += 1
                continue
            return {"entry_ts": decision, "entry_index": at, "market_open": market_open,
                    "direction": "long" if sign == 1 else "short", "stop": stop, "target": target,
                    "zone": z, "atr15": atr, "breakout_ts": five[state["index"]]["t"],
                    "confirmation_ts": b["t"], "selection_reward_risk": sign * (target - fill) / risk}
        active = next_active
        avg_volume = mean(x["v"] for x in five[i-6:i])
        if avg_volume <= 0 or b["v"] < 1.25 * avg_volume or body < .5:
            continue
        for z in zones:
            sign = 1 if five[i-1]["c"] <= z["high"] and b["c"] > z["high"] + .1 * atr and b["c"] > b["o"] else (
                -1 if five[i-1]["c"] >= z["low"] and b["c"] < z["low"] - .1 * atr and b["c"] < b["o"] else 0)
            if sign and not any(s["zone"] == z and s["sign"] == sign for s in active):
                active.append({"zone": z, "sign": sign, "index": i})
                rejected["qualified_breakouts"] += 1
    return None


def replay(candidate, forward, hold, bps):
    sign = 1 if candidate["direction"] == "long" else -1
    fill = forward[0]["o"] * (1 + sign * bps / 20000)
    stop, target = candidate["stop"], candidate["target"]
    risk = sign * (fill - stop)
    if risk <= 0 or sign * (target - fill) <= 0:
        raise ValueError("Invalid fill geometry")
    bars = forward[:hold]
    price, exit_ts, reason = bars[-1]["c"], bars[-1]["t"] + 60000, "TIME"
    for bar in bars:
        if sign * (bar["o"] - stop) <= 0:
            price, exit_ts, reason = bar["o"], bar["t"], "GAP_STOP"
            break
        stop_hit = bar["l"] <= stop if sign == 1 else bar["h"] >= stop
        target_hit = bar["h"] >= target if sign == 1 else bar["l"] <= target
        if stop_hit or target_hit:
            price, exit_ts, reason = (stop, bar["t"], "STOP") if stop_hit else (target, bar["t"], "TARGET")
            break
    pnl = sign * (price - fill) - fill * bps / 20000
    return {"r": pnl / risk, "underlying_pnl_pct": 100 * pnl / fill,
            "exit_ts": exit_ts, "exit_reason": reason, "fill": fill, "exit_reference": price}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--design", required=True)
    p.add_argument("--cache", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()
    design_path = Path(args.design)
    design_bytes = design_path.read_bytes()
    design_hash = hashlib.sha256(design_bytes).hexdigest()
    if design_hash != "72d3913034a3afcd2ac618c36f99949afcd887ad3c48e061362ee79f742e0278":
        raise ValueError("Frozen design changed; declare a new experiment rather than silently retuning")
    design = json.loads(design_bytes)
    start, end = design["period"]
    if end > DEVELOPMENT_END:
        raise ValueError("Refusing to open dates beyond the previously examined development period")
    if design["version"] != "historical-zone-retest-v1":
        raise ValueError("Unsupported frozen specification")
    schedule = market_schedule(start, end)
    report = {"design": design, "design_sha256": design_hash,
              "engine_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "stage": "development diagnostic; not independent validation or options P&L",
              "coverage": {}, "results": {}, "source_sha256": {}, "ledger": []}
    samples = {}
    for ticker in design["symbols"]:
        source = Path(args.cache) / f"{ticker}-{start}-{end}-1m.json"
        raw = source.read_bytes()
        report["source_sha256"][ticker] = hashlib.sha256(raw).hexdigest()
        days = session_bars(json.loads(raw))
        valid, gaps = {}, []
        for date, opened, closed in schedule:
            expected = range(opened, closed, 60000)
            missing = [ts for ts in expected if ts not in days.get(date, {})]
            if missing:
                gaps.append({"date": date, "missing_minutes": len(missing)})
            else:
                valid[date] = [days[date][ts] for ts in expected]
        rejected = Counter()
        eligible = 0
        for index in range(5, len(schedule)):
            date = schedule[index][0]
            prior = [x[0] for x in schedule[index-5:index]]
            if date not in valid or any(d not in valid for d in prior):
                rejected["missing_current_or_required_prior_session"] += 1
                continue
            eligible += 1
            zones, atr = historical_zones([(d, valid[d]) for d in prior])
            if len(zones) < 2:
                rejected["fewer_than_two_historical_zones"] += 1
                continue
            c = first_candidate(valid[date], zones, atr, rejected)
            if c is None:
                continue
            forward = valid[date][c["entry_index"]:]
            ledger = {"ticker": ticker, "date": date, **c, "outcomes": {}}
            for hold in design["rules"]["max_hold_minutes"]:
                for cost in design["rules"]["round_trip_underlying_cost_scenarios_bps"]:
                    outcome = replay(c, forward, hold, cost)
                    key = f"{ticker}:{hold}m:{cost}bps"
                    samples.setdefault(key, []).append({"date": date, **outcome})
                    ledger["outcomes"][f"{hold}m:{cost}bps"] = outcome
            report["ledger"].append(ledger)
        report["coverage"][ticker] = {"expected_sessions": len(schedule), "complete_sessions": len(valid),
                                       "gaps": gaps, "eligible_sessions_after_warmup": eligible,
                                       "rejections_and_events": dict(rejected)}
        print(ticker, "selected entries", sum(x["ticker"] == ticker for x in report["ledger"]), flush=True)
    for hold in design["rules"]["max_hold_minutes"]:
        for cost in design["rules"]["round_trip_underlying_cost_scenarios_bps"]:
            samples[f"COMBINED:{hold}m:{cost}bps"] = [row for ticker in design["symbols"]
                                                        for row in samples.get(f"{ticker}:{hold}m:{cost}bps", [])]
    for key, rows in samples.items():
        report["results"][key] = summary(rows)
        report["results"][key]["by_period"] = {
            "2024-2025": summary([r for r in rows if r["date"] < "2026-01-01"]),
            "2026-through-July17": summary([r for r in rows if r["date"] >= "2026-01-01"])}
    Path(args.output).write_text(json.dumps(report, indent=2))
    for key, result in report["results"].items():
        if key.startswith("COMBINED"):
            print(key, json.dumps(result), flush=True)


if __name__ == "__main__":
    main()
