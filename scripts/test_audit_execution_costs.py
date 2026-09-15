import unittest
from audit_execution_costs import replay, session_bars


class ExecutionReplayTest(unittest.TestCase):
    def test_cost_changes_the_target_path_not_just_final_profit(self):
        candidate = {"direction": "long", "sl": 99.9, "target_r": 1.0}
        bars = [{"t": 1, "o": 100, "h": 100.15, "l": 99.95, "c": 100.01}]
        free = replay(candidate, bars, 0)
        costly = replay(candidate, bars, 10)
        self.assertEqual("TARGET", free["reason"])
        self.assertAlmostEqual(1.0, free["r"])
        self.assertEqual("TIMEOUT", costly["reason"])
        self.assertLess(costly["r"], 0)

    def test_stop_first_when_both_levels_are_touched(self):
        for direction, stop in [("long", 99), ("short", 101)]:
            candidate = {"direction": direction, "sl": stop, "target_r": 1.0}
            result = replay(candidate, [{"t": 1, "o": 100, "h": 102, "l": 98, "c": 100}], 0)
            self.assertEqual("STOP", result["reason"])
            self.assertAlmostEqual(-1.0, result["r"])

    def test_gap_stop_fills_at_open(self):
        c = {"direction": "long", "sl": 99, "target_r": 2.0}
        bars = [{"t": 1, "o": 100, "h": 100.1, "l": 99.9, "c": 100},
                {"t": 2, "o": 98, "h": 98.5, "l": 97.5, "c": 98}]
        result = replay(c, bars, 0)
        self.assertEqual("GAP_STOP", result["reason"])
        self.assertAlmostEqual(-2.0, result["r"])

    def test_sub_basis_point_profit_is_preserved(self):
        c = {"direction": "long", "sl": 99.98766, "target_r": 2.0}
        result = replay(c, [{"t": 1, "o": 100, "h": 100.03, "l": 99.999, "c": 100}], 0)
        self.assertAlmostEqual(0.02468, result["pnl_pct"])
        self.assertAlmostEqual(2.0, result["r"])

    def test_duplicate_market_bars_are_rejected(self):
        b = {"t": 1780320600000, "o": 100, "h": 101, "l": 99, "c": 100, "v": 10}
        with self.assertRaises(ValueError):
            session_bars([b, b])


if __name__ == "__main__":
    unittest.main()
