"""
Example bot: places real spot market orders on Binance based on a simple
price-threshold signal. This is a skeleton for you to adapt your own
strategy into -- the order-placement, safety rails, and logging plumbing
are the point; the "buy below X / sell above Y" logic is just a stand-in.

ALWAYS test against Binance's testnet first (the default here). Flipping to
live trading is a deliberate, explicit step -- see LIVE_TRADING below -- and
you are solely responsible for whatever orders this places with real funds.
Start with the smallest quote amount Binance allows and watch the Logs
screen closely the first few times it trades.

Env keys (configured in the app when you add this bot):
    BINANCE_API_KEY        required
    BINANCE_API_SECRET     required
    SYMBOL                 default "BTCUSDT"
    TRADE_QUOTE_QTY        default "15"   -- quote-asset amount per order (USDT)
    BUY_BELOW              optional price; market-buy once if price drops to/below this
    SELL_ABOVE             optional price; market-sell the position once if price rises to/above this
    LIVE_TRADING           default "false" -- must be the literal string "true" to
                           trade on real Binance instead of testnet
    POLL_SECONDS           default "10"
"""
import time

from binance.client import Client
from binance.exceptions import BinanceAPIException


def main(stop_checker, env):
    api_key = env.get("BINANCE_API_KEY", "")
    api_secret = env.get("BINANCE_API_SECRET", "")
    if not api_key or not api_secret:
        print("BINANCE_API_KEY / BINANCE_API_SECRET not set, exiting")
        return

    symbol = env.get("SYMBOL") or "BTCUSDT"
    quote_qty = float(env.get("TRADE_QUOTE_QTY") or "15")
    buy_below = _to_float(env.get("BUY_BELOW"))
    sell_above = _to_float(env.get("SELL_ABOVE"))
    poll_seconds = float(env.get("POLL_SECONDS") or "10")
    live_trading = (env.get("LIVE_TRADING") or "false").strip().lower() == "true"

    client = Client(api_key, api_secret, testnet=not live_trading)
    print(f"binance_trading_bot started: symbol={symbol} testnet={not live_trading} "
          f"buy_below={buy_below} sell_above={sell_above} quote_qty={quote_qty}")
    if not live_trading:
        print("LIVE_TRADING is not 'true' -- running against Binance TESTNET only, "
              "no real funds are at risk.")

    has_open_position = False  # tracks whether we're holding after a buy, for the sell leg

    while not stop_checker.isStopped():
        try:
            price = float(client.get_symbol_ticker(symbol=symbol)["price"])
            print(f"{symbol} price = {price}")

            if not has_open_position and buy_below is not None and price <= buy_below:
                order = client.order_market_buy(symbol=symbol, quoteOrderQty=quote_qty)
                print(f"BUY filled: orderId={order.get('orderId')} status={order.get('status')}")
                has_open_position = True

            elif has_open_position and sell_above is not None and price >= sell_above:
                base_asset = symbol[:-len("USDT")] if symbol.endswith("USDT") else symbol
                balance = client.get_asset_balance(asset=base_asset)
                qty = float(balance["free"]) if balance else 0.0
                if qty > 0:
                    order = client.order_market_sell(symbol=symbol, quantity=qty)
                    print(f"SELL filled: orderId={order.get('orderId')} status={order.get('status')}")
                has_open_position = False

        except BinanceAPIException as exc:
            print(f"Binance API error: {exc.status_code} {exc.message}")
        except Exception as exc:
            print(f"unexpected error: {exc}")

        slept = 0.0
        while slept < poll_seconds and not stop_checker.isStopped():
            time.sleep(0.2)
            slept += 0.2

    print("binance_trading_bot stopping cleanly")


def _to_float(value):
    try:
        return float(value) if value else None
    except ValueError:
        return None
