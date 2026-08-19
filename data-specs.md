# HomeWx data sources & refresh behavior

Reflects the code as of this writing. File:line references point at the source of truth if this drifts out of date.

## 1. URLs

### Weather — Open-Meteo (`weather/openmeteo/OpenMeteoWeatherProvider.kt`)

| Purpose | URL | Notes |
|---|---|---|
| Current conditions + forecast | `https://api.open-meteo.com/v1/forecast` | `BASE_URL`, line 187 |
| Historical daily average (same-week-last-year comparison) | `https://archive-api.open-meteo.com/v1/archive` | `ARCHIVE_URL`, line 188 |

Common query params on every `BASE_URL` request: `latitude`, `longitude`, `temperature_unit=fahrenheit`, `wind_speed_unit=mph`, `precipitation_unit=inch`, `timezone=auto`.

- **Current conditions** adds `current=temperature_2m,relative_humidity_2m,apparent_temperature,wind_speed_10m,wind_direction_10m,precipitation,surface_pressure,weather_code,is_day`
- **Forecast** adds `hourly=temperature_2m,wind_speed_10m,precipitation_probability,surface_pressure,weather_code`, `daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code,wind_speed_10m_max`, `forecast_days=<Settings value, default 7>`
- **Archive** request: `latitude`, `longitude`, `start_date`, `end_date`, `daily=temperature_2m_max,temperature_2m_min`, `temperature_unit=fahrenheit`, `timezone=auto`

### RSS news feeds (`news/NewsModels.kt`, lines 11-17)

| Source | URL |
|---|---|
| WMUR | `https://www.wmur.com/topstories-rss` |
| WBZ (CBS Boston) | `https://www.cbsnews.com/boston/latest/rss/local-news` |

(WBZ's own breaking-news feed is only populated during live events, so the local-news feed is used instead — see the comment at that line.)

### Indoor sensors — Govee API

Not weather/RSS, but included for context since it feeds the SENSORS list and the sensor-history graphs. See `reference_homewx_govee_api` memory / `data/GoveeApiClient.kt` for endpoint details — not re-verified in this pass.

## 2. Update schedule

All driven by `HomeWxMonitorService.kt`, which runs three independent polling loops, all started in `onCreate()`:

| Loop | Interval | Notes |
|---|---|---|
| Govee sensors | 2 min while `LightMode.ACTIVE`, 15 min while `QUIET` | `ACTIVE_POLL_INTERVAL_MS`/`QUIET_POLL_INTERVAL_MS`, lines 268-273 |
| Weather (current + forecast, together) | Every `AppSettings.getWeatherSampleIntervalMinutes()` minutes — **default 30 min**, user-configurable in Settings (min 1 min) | line 149. Not affected by Active/Quiet. |
| Historical daily average (archive) | Once per calendar day | gated by `historicalAverageFetchedForDay`, lines 236-246 |
| RSS news (both feeds) | Every 10 min | `NEWS_POLL_INTERVAL_MS`, lines 151-160. Not affected by Active/Quiet. |

Important: **current conditions and the daily forecast are fetched together, on the same tick** — there's no separate/slower cadence for the forecast. Every ~30 min (default), both refresh.

Each tick also writes to local history stores that the graph panels read from:
- `sensorHistoryStore.record(...)` — every sensor poll (2 min Active / 15 min Quiet)
- `weatherMetricsHistoryStore.record(...)` — every weather poll (default every 30 min)

## 3. Behavior if the device is left running continuously

**RSS feeds** — yes, they reload on their own, every **10 minutes**, for as long as the app/service is running (this is a background service, not tied to which screen/panel is visible). The News tab re-renders automatically whenever new items come in (`MainActivity.observeState()` calls `newsPanel.onStateUpdated(...)` on every state tick — it only visibly changes when the feed content actually changed).

**Forecast data** — yes, reloads on its own too, on the **weather loop's cadence (default 30 min, user-adjustable in Settings)**. If the Forecast tab happens to be open when new data arrives, it auto-refreshes in place. If a different tab is open, the new data is simply waiting the next time you switch to Forecast.

**Graph panels — now also auto-update while left open** (as of this update). `MainActivity.observeState()` calls `refreshOpenPanelIfNeeded()` on every state tick, which re-renders whichever of these is currently showing:
- **Weather-graphs panel** (wind speed / precipitation / pressure history) — re-queries `weatherMetricsHistoryStore` on every state tick while open, so it picks up new rows as soon as they land (every ~30 min by default).
- **Sensor-graphs panel** (per-sensor temperature history, all visible sensors) — same: re-queries `sensorHistoryStore` for every visible sensor on every tick while open (new rows every 2 min Active / 15 min Quiet).
- **Single-sensor strip chart** (tap a row in the SENSORS list) — same: re-queries that one sensor's history on every tick while open.

Since all three re-query on *every* `AppState` tick (sensor poll, weather poll, or news poll — whichever fired), they may occasionally re-run a query whose underlying data didn't actually change; that's a deliberate simplicity trade-off (matches how the News/Forecast panels already worked) rather than a bug.

## 4. Auto tab-cycling after waking from Quiet

New behavior, implemented in `MainActivity.kt`:

- When the room light sensor drives a **QUIET → ACTIVE** transition (detected in `observeState()` via `handleLightModeTransition()`, comparing to the previous tick's light mode — this does *not* fire for the tap-to-wake override, which never changes `state.lightMode`), the info panel is forced to the **News** tab.
- 5 minutes after that, it auto-advances to the next tab, then keeps advancing every 5 minutes, cycling through **News → Weather graphs → Forecast → Sensor graphs → News → ...** (`AUTO_CYCLE_TABS`, `AUTO_CYCLE_INTERVAL_MS`).
- **Tapping any tab in the tab bar stops the auto-cycle immediately** (`selectTab(panel)` with a real tap defaults `isUserAction = true`, which cancels the cycle job). It does not resume on its own — the next QUIET → ACTIVE transition starts a fresh cycle.
- Going back to QUIET also stops the cycle (so a later wake always starts over at News rather than resuming mid-rotation).
- Opening the single-sensor strip chart or an article does *not* stop the cycle (only the 4 tab-bar buttons do, per spec) — if the cycle fires while one of those is open, it will switch away from it back into the rotation.
