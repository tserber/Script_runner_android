"""
Example bot: prints the current price for a symbol every few seconds using
the public Binance REST API (read-only, no keys needed for ticker data).

Env keys (optional, all have defaults):
    BINANCE_API_KEY, BINANCE_API_SECRET  -- only needed if you extend this
                                             into an order-placing bot
    SYMBOL                               -- defaults to BTCUSDT
"""
import time
import requests


def main(stop_checker, env):
    symbol = env.get("SYMBOL") or "BTCUSDT"
    print(f"binance_ticker_bot started for {symbol}")

    while not stop_checker.isStopped():
        try:
            resp = requests.get(
                "https://api.binance.com/api/v3/ticker/price",
                params={"symbol": symbol},
                timeout=10,
            )
            price = resp.json().get("price")
            print(f"{symbol} = {price}")
        except Exception as exc:
            print(f"fetch error: {exc}")

        for _ in range(50):  # ~5s total, but checked often so Stop is snappy
            if stop_checker.isStopped():
                break
            time.sleep(0.1)

    print("binance_ticker_bot stopping cleanly")
