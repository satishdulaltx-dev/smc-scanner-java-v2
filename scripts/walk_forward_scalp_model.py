#!/usr/bin/env python3
"""Evaluate a fixed ridge scorer on point-in-time scalp candidates using expanding monthly folds."""

import argparse
import json
import math
import pathlib
import re
from collections import defaultdict
from datetime import datetime
from zoneinfo import ZoneInfo

import numpy as np

ET = ZoneInfo("America/New_York")
RIDGE_PENALTY = 10.0
SELECTION_QUANTILE = 0.80
MINIMUM_TRAINING_ROWS = 200
MINIMUM_EVIDENCE_TRADES = 30


def recorded_or_derived_features(candidate):
    recorded = candidate.get("features") or {}
    if recorded:
        return {name: float(value) for name, value in recorded.items()}
    market = float(candidate["market_open"])
    signal = float(candidate.get("signal_entry", market))
    stop = float(candidate["sl"])
    atr = max(float(candidate.get("atr", 0)), market * 0.00001)
    direction = 1.0 if candidate["direction"] == "long" else -1.0
    at = datetime.fromtimestamp(candidate["entry_ts"] / 1000, ET)
    factors = candidate.get("factor_breakdown", "")

    def factor(name):
        match = re.search(rf"(?:^|\|)\s*{re.escape(name)}=([-+]?\d+(?:\.\d+)?)", factors)
        return float(match.group(1)) if match else 0.0

    vwap = factor("VWAP")
    level = factor("10m level")
    gates = candidate.get("gate_pass", {})
    features = {
        "direction_long": 1.0 if direction > 0 else 0.0,
        "minutes_from_open": max(0.0, (at.hour * 60 + at.minute) - (9 * 60 + 30)),
        "confidence": float(candidate.get("confidence", 0)),
        "atr_pct": atr / market,
        "risk_pct": abs(signal - stop) / market,
        "risk_atr": abs(signal - stop) / atr,
        "directional_open_gap_atr": direction * (market - signal) / atr,
        "directional_move_pct": factor("move") / 100.0,
        "directional_vwap_distance_atr": direction * (signal - vwap) / atr if vwap else 0.0,
        "breakout_distance_atr": direction * (signal - level) / atr if level else 0.0,
    }
    for gate in ("spy", "15m", "volume", "regime", "time", "cost"):
        features[f"gate_{gate}"] = 1.0 if gates.get(gate, False) else 0.0
    return features


def load_rows(directory, development_end):
    rows = []
    files = sorted(pathlib.Path(directory).glob("*.json"))
    if not files:
        raise ValueError("No JSON experiment files found")
    for path in files:
        payload = json.loads(path.read_text())
        payload = payload.get("result", payload)
        if "candidate_ledger" not in payload:
            continue
        if payload.get("error"):
            raise ValueError(f"{path.name}: {payload['error']}")
        if payload.get("filters"):
            raise ValueError(f"{path.name}: model input must come from an ungated experiment")
        if float(payload.get("target_r", 2.0)) != 2.0:
            raise ValueError(f"{path.name}: this fixed model study requires 2R labels")
        if payload.get("end_date", "9999-12-31") > development_end:
            raise ValueError(f"{path.name}: input opens data after the declared development end")
        ticker = payload["ticker"]
        for candidate in payload.get("candidate_ledger", []):
            outcome = candidate.get("exits", {}).get("FIXED_R")
            if not outcome:
                continue
            feature_values = recorded_or_derived_features(candidate)
            if not all(math.isfinite(value) for value in feature_values.values()):
                raise ValueError(f"{path.name}: candidate {candidate.get('id')} has a non-finite feature")
            risk_multiple = float(outcome["risk_multiple"])
            if not math.isfinite(risk_multiple):
                raise ValueError(f"{path.name}: candidate {candidate.get('id')} has a non-finite outcome")
            at = datetime.fromtimestamp(candidate["entry_ts"] / 1000, ET)
            rows.append({"ticker": ticker, "entry_ts": candidate["entry_ts"],
                         "month": at.strftime("%Y-%m"), "date": at.date().isoformat(),
                         "features": feature_values, "r": risk_multiple,
                         "baseline_selected": bool(candidate.get("selected"))})
    rows.sort(key=lambda row: row["entry_ts"])
    if not rows:
        raise ValueError("No selected candidates with point-in-time features were found")
    return rows


def matrix(rows, names):
    return np.asarray([[float(row["features"].get(name, 0.0)) for name in names] for row in rows], dtype=float)


def fit_predict(train, test, names):
    x_train = matrix(train, names)
    y_train = np.asarray([row["r"] for row in train], dtype=float)
    mean = x_train.mean(axis=0)
    scale = x_train.std(axis=0)
    scale[scale < 1e-9] = 1.0
    z_train = (x_train - mean) / scale
    z_train = np.column_stack([np.ones(len(z_train)), z_train])
    penalty = np.eye(z_train.shape[1]) * np.sqrt(RIDGE_PENALTY)
    penalty[0, 0] = 0
    augmented_x = np.vstack([z_train, penalty])
    augmented_y = np.concatenate([y_train, np.zeros(z_train.shape[1])])
    weights = np.linalg.lstsq(augmented_x, augmented_y, rcond=None)[0]
    train_scores = np.sum(z_train * weights, axis=1)
    threshold = float(np.quantile(train_scores, SELECTION_QUANTILE))
    z_test = (matrix(test, names) - mean) / scale
    z_test = np.column_stack([np.ones(len(z_test)), z_test])
    return np.sum(z_test * weights, axis=1), threshold


def summarize(rows):
    values = np.asarray([row["r"] for row in rows], dtype=float)
    if not len(values):
        return {"trades": 0, "mean_r": 0, "total_r": 0, "profit_factor": 0,
                "ci95": [0, 0], "positive_months": 0, "active_months": 0,
                "verdict": "INSUFFICIENT_SAMPLE"}
    gains = values[values > 0].sum()
    losses = -values[values < 0].sum()
    rng = np.random.default_rng(20260909)
    sampled = rng.choice(values, size=(10000, len(values)), replace=True).mean(axis=1)
    monthly = defaultdict(float)
    for row in rows:
        monthly[row["month"]] += row["r"]
    low, high = np.quantile(sampled, [0.025, 0.975])
    positive = sum(value > 0 for value in monthly.values())
    if high <= 0:
        verdict = "REJECTED"
    elif len(values) < MINIMUM_EVIDENCE_TRADES:
        verdict = "INSUFFICIENT_SAMPLE"
    elif low > 0 and positive >= np.ceil(len(monthly) * 0.60):
        verdict = "PROMISING_TRAINING_ONLY"
    else:
        verdict = "INCONCLUSIVE"
    return {"trades": len(values), "wins": int((values > 0).sum()),
            "mean_r": round(float(values.mean()), 4), "total_r": round(float(values.sum()), 3),
            "profit_factor": round(float(gains / losses), 3) if losses else 999.0,
            "ci95": [round(float(low), 4), round(float(high), 4)],
            "positive_months": positive, "active_months": len(monthly), "verdict": verdict}


def walk_forward(rows):
    feature_names = sorted(rows[0]["features"])
    if any(sorted(row["features"]) != feature_names for row in rows):
        raise ValueError("Feature schema changed within the input data")
    months = sorted({row["month"] for row in rows})
    selected, baseline, folds = [], [], []
    for month in months:
        train = [row for row in rows if row["month"] < month]
        test = [row for row in rows if row["month"] == month]
        if len(train) < MINIMUM_TRAINING_ROWS or not test:
            continue
        scores, threshold = fit_predict(train, test, feature_names)
        chosen = []
        used_sessions = set()
        for row, score in zip(test, scores):
            session = (row["ticker"], row["date"])
            if score < threshold or session in used_sessions:
                continue
            chosen.append(row)
            used_sessions.add(session)
        selected.extend(chosen)
        baseline.extend(row for row in test if row["baseline_selected"])
        folds.append({"month": month, "training_rows": len(train), "test_rows": len(test),
                      "selected_rows": len(chosen), "threshold": round(threshold, 4),
                      "selected_mean_r": round(sum(row["r"] for row in chosen) / len(chosen), 4) if chosen else 0})
    return {"method": "expanding monthly ridge; fixed penalty 10; train-score 80th percentile entry gate",
            "features": feature_names, "folds": folds,
            "baseline": summarize(baseline), "walk_forward_selected": summarize(selected)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True)
    parser.add_argument("--development-end", default="2026-07-17")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    rows = load_rows(args.input_dir, args.development_end)
    report = {"development_end": args.development_end, "validation_opened": False,
              "source_rows": len(rows), "source_tickers": sorted({row["ticker"] for row in rows}),
              "source_range": [rows[0]["date"], rows[-1]["date"]],
              "limitations": ["development data only", "fixed existing scalp candidate generator",
                              "fixed 30-minute 2R labels with modeled friction",
                              "a promising result still requires untouched validation"],
              **walk_forward(rows)}
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2))
    print(json.dumps({"source_rows": len(rows), "baseline": report["baseline"],
                      "walk_forward_selected": report["walk_forward_selected"], "output": str(output)}, indent=2))


if __name__ == "__main__":
    main()
