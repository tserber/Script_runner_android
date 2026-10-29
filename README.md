# Script_runner_android

Android app that runs Python scripts (trading bots, Telegram bots, etc.) as
background services, with per-bot Start / Stop / Restart, live status, a log
console, and encrypted secret storage. Python runs *inside* the app via
[Chaquopy](https://chaquo.com/chaquopy/) — a real embedded CPython, not a
sandboxed subset, so `pip` packages with native wheels (`requests`,
`python-telegram-bot`, `python-binance`, …) work normally.

## What's in this repo

```
app/src/main/java/com/botrunner/app/
  MainActivity.kt            Compose UI: bot list, add/edit dialog, log viewer
  BotRunnerApp.kt            Application class, starts Chaquopy, sets up the notification channel
  data/
    BotConfig.kt, BotDao.kt, AppDatabase.kt   Room entity/DAO/DB for saved bot configs
    SecretsStore.kt           Encrypted (Android Keystore) storage for API keys/tokens
  runner/
    BotRunnerManager.kt       Runs each bot's Python on its own thread via Chaquopy; tracks status
    BotRunnerService.kt       Foreground service + persistent "N bot(s) running" notification
    BootRestartReceiver.kt    Restarts "start on boot" bots after a device reboot
    LogBuffer.kt, PythonBridge.kt   stdout/stderr capture, per-bot log ring buffer

app/src/main/python/examples/
  telegram_echo_bot.py        Polls Telegram, echoes messages back (read/write, needs a bot token)
  binance_ticker_bot.py       Prints a symbol's price on a loop (read-only, no keys needed)
  binance_trading_bot.py      Places real market orders on a price threshold (testnet by default)

.github/workflows/android-build.yml   CI: lints, tests, builds a debug APK on every push/PR
```

Everything else (`build.gradle.kts`, `settings.gradle.kts`, `gradle/`,
`app/src/main/res/`, `AndroidManifest.xml`) is standard Gradle/Android
project scaffolding.

## How to compile it

You don't need to touch the command line if you don't want to:

**Option A — Android Studio (recommended if you're not an Android dev)**
1. Install [Android Studio](https://developer.android.com/studio) (includes
   the Android SDK). During first run, also install the **NDK** via
   `Settings → Languages & Frameworks → Android SDK → SDK Tools` (Chaquopy
   needs it to unpack its bundled CPython).
2. `File → Open` → select this repo's root folder. Let it sync (first sync
   downloads Chaquopy's Python distribution + the `pip` packages listed in
   `app/build.gradle.kts`; needs network access, takes a few minutes).
3. Plug in an Android phone (with USB debugging on) or start an emulator,
   then press **Run ▶**. That's it — this installs and launches the app.
4. To get an installable `.apk` file instead: `Build → Build App Bundle(s) / APK(s) → Build APK(s)`.
   It lands under `app/build/outputs/apk/debug/`.

**Option B — command line**
```bash
./gradlew installDebug      # build + install on a connected device/emulator
./gradlew assembleDebug     # just build the APK, found under app/build/outputs/apk/debug/
```
(If `gradlew` is missing locally, run `gradle wrapper --gradle-version 8.7`
once — Android Studio does this for you automatically on first open.)

**Option C — let GitHub build it for you (no Android Studio at all)**
Every push to this repo triggers `.github/workflows/android-build.yml`,
which builds a debug APK in the cloud. On GitHub, open this repo →
**Actions** tab → the latest run → scroll to **Artifacts** → download
`bot-runner-debug-apk`. Unzip it, transfer the `.apk` to your phone, open it,
and allow "install unknown apps" when Android prompts. No local build tools
needed.

> Only `assembleDebug` is wired up (CI and Option B). There's no release
> signing config yet — see **Known gaps** below.

## How to use it

1. Open the app. The list starts empty.
2. Tap **+** to add a bot:
   - **Display name** — anything you want shown on its card.
   - **Python module** — the dotted path under `app/src/main/python/`, e.g.
     `examples.binance_ticker_bot` or `examples.telegram_echo_bot`.
   - **Secret/env keys** — comma-separated names the script expects (see
     each script's own docstring for which ones it reads), e.g.
     `TELEGRAM_TOKEN` or `BINANCE_API_KEY,BINANCE_API_SECRET`. A masked input
     box appears for each key you list — values go straight into encrypted
     storage, never into the saved bot config.
   - **Auto-restart on crash** / **Start on device boot** toggles.
3. Tap **Start** on a bot's card. Its status chip goes
   Starting → Running; a persistent notification appears showing how many
   bots are active.
4. Tap **Logs** to watch its stdout/stderr live.
5. Tap **Stop** to end it cleanly, or **Restart** to stop-then-start it. If a
   bot crashes, the card shows the error and restart count (and auto-restarts
   if that toggle was on, up to `maxRestarts`).
6. **Delete** stops the bot and wipes its stored secrets.
7. Leaving the app doesn't stop anything — bots keep running in the
   background via the foreground service; pull down the notification for a
   global **Stop all**.

To add your own bot: drop a new `.py` file under `app/src/main/python/`
exporting `def main(stop_checker, env: dict) -> None`, loop while
`not stop_checker.isStopped()`, add any extra `pip` package it needs to the
`chaquopy { pip { ... } }` block in `app/build.gradle.kts`, re-sync/rebuild,
then add it from the **+** dialog like any other bot.

### `examples.binance_trading_bot`

Places real spot market orders based on a simple price-threshold signal —
meant as a skeleton to adapt your own strategy into, not a strategy itself.
Defaults to Binance **testnet**; trading with real funds requires explicitly
setting `LIVE_TRADING=true` as one of the bot's env keys. Start with the
smallest `TRADE_QUOTE_QTY` Binance allows and watch the Logs screen closely.

## Runtime architecture

- **`BotRunnerManager`** (plain Kotlin singleton, no Android deps): owns one
  daemon thread per running bot, calls straight into the embedded CPython via
  Chaquopy, and exposes a `StateFlow<Map<botId, BotRunState>>` with
  `STOPPED / STARTING / RUNNING / STOPPING / CRASHED`, plus restart counts and
  the last error. Crash → auto-restart with backoff, capped by
  `BotConfig.maxRestarts`, when `autoRestartOnCrash` is on.
- **stdout/stderr capture**: each bot's `sys.stdout`/`sys.stderr` are swapped
  to a small Kotlin `LogSink` for the duration of the call (restored
  afterwards), so `print()` output is captured without the bot script having
  to know anything about Android. Lines land in a 500-line-per-bot ring
  buffer (`LogBuffer`) viewable live from the app's log dialog.
- **`BotRunnerService`**: a foreground service holding a persistent
  notification ("N bot(s) running", with a Stop-all action) so Android
  doesn't kill the process while bots run in the background. It only
  forwards Start/Stop/Restart intents into `BotRunnerManager` — it holds no
  bot state itself, so the UI keeps working correctly across process death
  and Activity recreation.
- **`BootRestartReceiver`**: on device boot, restarts every bot flagged
  "start on boot".
- **Secrets**: `SecretsStore` wraps `EncryptedSharedPreferences`
  (AES-256-GCM via Android Keystore), keyed `"<botId>:<envKeyName>"`. Secrets
  are never written into `BotConfig`/Room, never logged, and excluded from
  Android backups (`backup_rules.xml`, `data_extraction_rules.xml`).
- **Persistence**: `Room` (`bots.db`) stores each `BotConfig` — name, module
  path, which env keys it needs, auto-restart/start-on-boot flags, max
  restarts. Secret *values* live only in `SecretsStore`, separate from this.

## CI

`.github/workflows/android-build.yml` runs on every push/PR: lint, unit
tests, `assembleDebug`, and uploads the resulting debug APK as a workflow
artifact (see Option C above for how to grab it).

## Known gaps to fill in before production use

- No code/app signing config for a release `.apk`/`.aab` — add a keystore and
  a `signingConfigs` block in `app/build.gradle.kts` before distributing
  outside your own device.
- No unit/integration tests yet.
- `pip` packages in `chaquopy { pip { ... } }` are pinned as an example set;
  trim or extend them to match the bots you actually ship — each one adds to
  APK size and build time.
