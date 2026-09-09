#!/usr/bin/env python3
"""Evaluate a fixed ridge scorer on point-in-time scalp candidates using expanding monthly folds."""

import argparse
import json
import pathlib
from collections import defaultdict
from datetime import datetime
from zoneinfo import ZoneInfo

import numpy as np

ET = ZoneInfo("America/New_York")
RIDGE_PENALTY = 10.0
SELECTION_QUANTILE = 0.80
MINIMUM_TRAINING_ROWS = 200
MINIMUM_EVIDENCE_TRADES = 30


def load_rows(directory, development_end):
    rows = []
    files = sorted(pathlib.Path(directory).glob("*.json"))
    if not files:
        raise ValueError("No JSON experiment files found")
    for path in files:
        payload = json.loads(path.read_text())
        payload = payload.get("result", payload)
        if payload.get("error"):
            raise ValueError(f"{path.name}: {payload['error']}")
        if payload.get("filters"):
            raise ValueError(f"{path.name}: model input must come from an ungated experiment")
        if payload.get("end_date", "9999-12-31") > development_end:
            raise ValueError(f"{path.name}: input opens data after the declared development end")
        ticker = payload["ticker"]
        for candidate in payload.get("candidate_ledger", []):
            if not candidate.get("selected") or not candidate.get("features"):
                continue
            outcome = candidate.get("exits", {}).get("FIXED_R")
            if not outcome:
                continue
            at = datetime.fromtimestamp(candidate["entry_ts"] / 1000, ET)
            rows.append({"ticker": ticker, "entry_ts": candidate["entry_ts"],
                         "month": at.strftime("%Y-%m"), "date": at.date().isoformat(),
                         "features": candidate["features"], "r": float(outcome["risk_multiple"])})
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
    penalty = np.eye(z_train.shape[1]) * RIDGE_PENALTY
    penalty[0, 0] = 0
    weights = np.linalg.solve(z_train.T @ z_train + penalty, z_train.T @ y_train)
    train_scores = z_train @ weights
    threshold = float(np.quantile(train_scores, SELECTION_QUANTILE))
    z_test = (matrix(test, names) - mean) / scale
    z_test = np.column_stack([np.ones(len(z_test)), z_test])
    return z_test @ weights, threshold


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
        chosen = [row for row, score in zip(test, scores) if score >= threshold]
        selected.extend(chosen)
        baseline.extend(test)
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
