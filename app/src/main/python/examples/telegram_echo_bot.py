"""
Example bot: polls Telegram getUpdates and echoes any text message back.

Env keys required (configured in the app when you add this bot):
    TELEGRAM_TOKEN

Every bot module loaded by BotRunnerManager must define:

    def main(stop_checker, env: dict) -> None

and return promptly once stop_checker.isStopped() is True -- the Kotlin side
blocks the dedicated bot thread on this call, so the loop IS the lifetime of
the "running" state.
"""
import time
import requests


def main(stop_checker, env):
    token = env.get("TELEGRAM_TOKEN", "")
    if not token:
        print("TELEGRAM_TOKEN is not set, exiting")
        return

    base = f"https://api.telegram.org/bot{token}"
    offset = None
    print("telegram_echo_bot started")

    while not stop_checker.isStopped():
        try:
            resp = requests.get(
                f"{base}/getUpdates",
                params={"timeout": 10, "offset": offset},
                timeout=15,
            )
            data = resp.json()
            for update in data.get("result", []):
                offset = update["update_id"] + 1
                message = update.get("message") or {}
                chat_id = message.get("chat", {}).get("id")
                text = message.get("text")
                if chat_id and text:
                    requests.post(
                        f"{base}/sendMessage",
                        json={"chat_id": chat_id, "text": f"echo: {text}"},
                        timeout=15,
                    )
                    print(f"replied to {chat_id}: {text}")
        except Exception as exc:
            print(f"poll error: {exc}")
            time.sleep(3)

    print("telegram_echo_bot stopping cleanly")
