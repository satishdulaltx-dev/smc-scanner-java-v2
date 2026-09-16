import unittest
from replay_zone_options import choose_contract, price_reference, reference_times


class OptionsReferenceTest(unittest.TestCase):
    def test_contract_selection_uses_known_terms_not_future_volume(self):
        candidate = {"date": "2026-06-01", "direction": "long", "market_open": 100.8}
        def contract(name, strike, expiry, volume):
            return {"ticker": name, "contract_type": "call", "shares_per_contract": 100,
                    "expiration_date": expiry, "strike_price": strike, "future_volume": volume}
        rows = [contract("far", 110, "2026-06-12", 100000),
                contract("nearest", 101, "2026-06-12", 1),
                contract("too-soon", 101, "2026-06-05", 100000),
                contract("later", 100.8, "2026-06-15", 100000)]
        self.assertEqual("nearest", choose_contract(rows, candidate)["ticker"])

    def test_missing_exit_is_not_filled_from_an_earlier_trade(self):
        rows = [{"t": 0, "o": 2, "v": 1}, {"t": 120000, "o": 2.2, "v": 1}]
        self.assertEqual("missing_exact_entry_or_exit_minute",
                         price_reference(rows, 60000, 120000, 180000)["status"])

    def test_no_history_before_signal_is_reported(self):
        rows = [{"t": 120000, "o": 2, "v": 1}, {"t": 180000, "o": 3, "v": 1}]
        self.assertEqual("no_trade_history_before_decision",
                         price_reference(rows, 60000, 120000, 180000)["status"])

    def test_touch_cannot_exit_at_price_before_touch_is_observable(self):
        candidate = {"entry_ts": 0, "outcomes": {"15m:0bps": {"exit_ts": 120000, "exit_reason": "STOP"}}}
        self.assertEqual((60000, 240000), reference_times(candidate))
        candidate["outcomes"]["15m:0bps"]["exit_ts"] = 0
        self.assertIsNone(reference_times(candidate))
        candidate["outcomes"]["15m:0bps"] = {"exit_ts": 900000, "exit_reason": "TIME"}
        self.assertEqual((60000, 960000), reference_times(candidate))


if __name__ == "__main__":
    unittest.main()
