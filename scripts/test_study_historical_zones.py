import unittest
from collections import Counter
from datetime import datetime
from zoneinfo import ZoneInfo

from study_historical_zones import aggregate, first_candidate, historical_zones, market_schedule, replay

ET = ZoneInfo("America/New_York")
OPEN = int(datetime(2026, 6, 1, 9, 30, tzinfo=ET).timestamp() * 1000)


def bar(t, o=99.9, h=100, l=99.7, c=99.9, v=20):
    return dict(t=t, o=o, h=h, l=l, c=c, v=v)


class HistoricalZoneTest(unittest.TestCase):
    def test_exchange_early_close_and_memorial_closure(self):
        session = market_schedule("2024-11-29", "2024-11-29")[0]
        self.assertEqual(210, (session[2] - session[1]) // 60000)
        self.assertEqual([], market_schedule("2025-01-09", "2025-01-09"))

    def test_missing_minutes_cannot_be_aggregated(self):
        bars = [bar(OPEN + i * 60000) for i in [0, 1, 2, 4, 5]]
        with self.assertRaises(ValueError):
            aggregate(bars, 5)

    def test_last_unconfirmed_pivot_is_never_a_zone(self):
        sessions = []
        for day in range(2):
            start = OPEN - (2 - day) * 86400000
            rows = []
            for i, high in enumerate([100, 101, 104, 101, 100, 100, 100, 500]):
                rows.extend(bar(start + (i * 15 + j) * 60000, h=high) for j in range(15))
            sessions.append((f"day-{day}", rows))
        zones, _ = historical_zones(sessions)
        self.assertTrue(zones)
        self.assertTrue(all(z["high"] < 200 for z in zones))
        self.assertTrue(all(len(z["sessions"]) == 2 for z in zones))

    def test_entry_follows_completed_retest_and_future_prices_do_not_change_it(self):
        bars = [bar(OPEN + i * 60000) for i in range(60)]
        for i in range(30, 35):
            bars[i] = bar(OPEN + i * 60000, 99.9, 100.65, 99.85, 100.6, 40)
        for i in range(35, 40):
            bars[i] = bar(OPEN + i * 60000, 100.1, 100.47, 99.98, 100.45, 20)
        bars[40] = bar(OPEN + 40 * 60000, 100.46, 100.5, 100.4, 100.48)
        zones = [{"low": 99.8, "high": 100, "confirmed_at": OPEN - 1, "sessions": ["a", "b"]},
                 {"low": 102, "high": 102.2, "confirmed_at": OPEN - 1, "sessions": ["a", "b"]}]
        prefix = first_candidate(bars[:41], zones, .5, Counter())
        self.assertIsNotNone(prefix)
        self.assertEqual(OPEN + 40 * 60000, prefix["entry_ts"])
        self.assertEqual(99.75, prefix["stop"])
        self.assertEqual(102, prefix["target"])
        bars[45:] = [bar(OPEN + i * 60000, 500, 600, 400, 500) for i in range(45, 60)]
        self.assertEqual(prefix, first_candidate(bars, zones, .5, Counter()))
        mirrored_bars = [{"t": x["t"], "o": 200-x["o"], "h": 200-x["l"],
                          "l": 200-x["h"], "c": 200-x["c"], "v": x["v"]} for x in bars[:41]]
        mirrored_zones = [{**z, "low": 200-z["high"], "high": 200-z["low"]} for z in zones]
        short = first_candidate(mirrored_bars, mirrored_zones, .5, Counter())
        self.assertEqual("short", short["direction"])
        self.assertEqual(98, short["target"])
        self.assertAlmostEqual(100.25, short["stop"])
        zones[0]["confirmed_at"] = OPEN
        with self.assertRaises(ValueError):
            first_candidate(bars, zones, .5, Counter())

    def test_gap_stop_and_ambiguous_bar_are_conservative(self):
        candidate = {"direction": "long", "stop": 99, "target": 102}
        result = replay(candidate, [bar(OPEN, 100, 103, 98, 100)], 15, 0)
        self.assertEqual("STOP", result["exit_reason"])
        self.assertEqual(-1, result["r"])
        result = replay(candidate, [bar(OPEN, 100, 101, 99.5, 100),
                                    bar(OPEN + 60000, 98, 99, 97, 98)], 15, 0)
        self.assertEqual("GAP_STOP", result["exit_reason"])
        self.assertEqual(-2, result["r"])

    def test_historical_target_stays_fixed_across_cost_scenarios(self):
        candidate = {"direction": "long", "stop": 99, "target": 102}
        bars = [bar(OPEN, 100, 102.1, 99.5, 102)]
        free, costly = replay(candidate, bars, 15, 0), replay(candidate, bars, 15, 10)
        self.assertEqual(102, free["exit_reference"])
        self.assertEqual(102, costly["exit_reference"])
        self.assertLess(costly["r"], free["r"])


if __name__ == "__main__":
    unittest.main()
