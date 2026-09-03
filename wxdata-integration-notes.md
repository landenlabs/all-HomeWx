# wxdata weather source — implementation notes

Status: **implemented**, against `WxData-debug-2.26.0903.aar` (Retrofit/RxJava removed,
`CompletableFuture`-based; 2.26.0903 also made the historical fetcher constructors public -
see §3). `WeatherSourceId.WXDATA` / `WxDataWeatherProvider` exist and build clean
(`compileDebugKotlin` + `assembleDebug` both pass). Not yet device-tested. Sections below
are kept as a reference for the mapping/design, updated to match what actually shipped —
see §7 for what changed from the original plan and what's still open.

Source repo for wxdata: `/Users/dennislang/opt/android/auto/max-auto-android-wxdata/`
(separate git repo, not part of this project; branch `dev-2.26.0720` with the refactor
still uncommitted in the working tree as of this writing).

## 1. Credentials

- API key lives in `local.properties` as `SUN_API_KEY` (added by the user, not committed —
  `local.properties` is gitignored like the existing `GOVEE_API_KEY`).
- Wire it the same way `GOVEE_API_KEY` already is in `app/build.gradle.kts`:
  - `local.properties` loaded into a `Properties` object (`app/build.gradle.kts:10-11`).
  - `val goveeApiKey = localProperties.getProperty("GOVEE_API_KEY") ?: ""` /
    `buildConfigField("String", "GOVEE_API_KEY", "\"$goveeApiKey\"")` (`app/build.gradle.kts:28-29`).
  - Mirror this for `SUN_API_KEY` -> `BuildConfig.SUN_API_KEY`, consumed by
    `WxDataWeatherProvider`/wherever `WxData.getInstance().initialize(...)` is called.

## 2. AAR / build wiring

- `app/libs/WxData-debug-2.26.0720.aar` exists but is **not yet referenced** in
  `app/build.gradle.kts` (54 lines, `dependencies` block at 53-66 as of this writing —
  zero mention of the AAR or of Retrofit/RxJava).
- Per the user: **use the debug AAR for both debug and release builds for now** — no
  separate release variant needed at this stage. Revisit if/when wxdata ships a release AAR.
- Original (pre-refactor) AAR needed Retrofit2 + RxJava3 as app-level deps (its own
  `build.gradle:184-195` declares them `implementation`, not `api`, except OkHttp which is
  `api` and already satisfied by HomeWx's existing `okhttp3:okhttp:5.5.0`). **This is going
  away** — wxdata is being refactored to drop Retrofit + RxJava, so don't add those
  dependencies until the new AAR drop confirms what it actually needs. Re-check the new
  AAR's transitive deps (or ask the user) before wiring `implementation(files(...))`.

## 3. API surface -> WeatherModels.kt mapping (from the pre-refactor source; re-verify after refactor)

Entry point: `WxData.getInstance().initialize(context, apiKey, InitializationListener, overrideConfig)`
(`WxDataHelper.java:60-78` in the sample) — async, must succeed once at app startup before
any fetch.

Fetchers, each built via `BaseWxWeatherBasedFetcher` (`setLocation`/`setUnit`/`setTime`,
`BaseWxWeatherBasedFetcher.java:28-99`):
- `WxCurrentFetcher` -> `WxCurrentConditions` (`WxCurrentConditions.java:55-103`)
- `WxHourlyFetcher` -> `WxHourlyForecast` (`WxHourlyForecast.java:55-101`)
- `WxDailyFetcher` -> `WxDailyForecast` (`WxDailyForecast.java:81-102, 460-481`)

`WxUnit.Imperial` is the default (°F/mph/inHg) — matches HomeWx, no conversion needed.

| HomeWx | wxdata |
|---|---|
| `CurrentConditions.temperatureF` | `WxCurrentConditions.temperature` |
| `.feelsLikeF` | `.temperatureFeelsLike` |
| `.humidityPct` | `.relativeHumidity` |
| `.windSpeedMph` / `.windDirectionDeg` | `.windSpeed` / `.windDirection` |
| `.precipitationIn` | `.precip1Hour` (rolling-hour, not instantaneous — closest match) |
| `.pressureInHg` | `.pressureAltimeter` |
| `.conditionText` / `.iconKey` | `.wxPhraseLong` / `.iconCode` (needs new icon-code -> drawable-key table; wxdata's numeric codes are not the WMO codes Open-Meteo uses) |
| `HourlyForecastEntry.timeMillis` | `WxHourlyForecast.validTimeUtc[i]` (seconds, ×1000) |
| `.temperatureF` / `.windSpeedMph` / `.pressureInHg` | `.temperature[i]` / `.windSpeed[i]` / `.pressureAltimeter[i]` |
| `.precipitationChancePct` | `.precipChance[i]` |
| `DailyForecastEntry.dateMillis` | `WxDailyForecast.validTimeUtc[i]` (×1000, then +12h per the noon-plotting fix already made in `OpenMeteoWeatherProvider.kt`) |
| `.highF` / `.lowF` | `.temperatureMax[i]` / `.temperatureMin[i]` |
| `.windMaxMph` | gap — no direct daily wind-max field seen; fallback to daypart max or `null` |
| `.precipitationChancePct` (daily) | `.daypart[i].precipChance` (needs day-vs-night part selection) |

### Historical data — resolved via WxAlmanacDailyFetcher, not WxDailyHistoricalFetcher

`WxDailyHistoricalFetcher()`/`WxHourlyHistoricalFetcher()` constructors are `public` as of
`WxData-debug-2.26.0903.aar` — confirmed via `javap -p` on the shipped `classes.jar`, and
in the wxdata source repo's working tree (only the fetcher constructors changed; the
`WxDailyHistorical`/`WxHourlyHistorical` data classes themselves have no diff). But that
access alone doesn't help: **`getDaily30DayHistoricalObservable(...)` (the network call
`WxDailyHistoricalFetcher.doFetch()` makes) takes no date-range parameter at all** —
confirmed both in source and by an actual failed request's URL (`.../historical/
dailysummary/30day?geocode=...&apiKey=...&language=...&units=...&format=...`, no date
anywhere) — it always returns TWC's trailing 30-days-from-now window server-side,
regardless of what `WxTime` is passed to `setTime()` (that value is only used for local
`setAuxData()` bookkeeping/labeling, never sent in the request). HomeWx's only caller,
`historicalComparisonWindow()` (±3 days around the same date **one year ago**), can never
get an overlap with a trailing-30-days-from-*now* window — so this fetcher is a dead end
for this feature specifically, constructor access notwithstanding.

**`WxAlmanacDailyFetcher` is the one that actually works**, and was already fully public
(constructor included) before any of this AAR-version chasing:

```java
// WxAlmanacDailyFetcher.doFetch():
Integer startDay = new WxDateTime(times.get(0)).getDayOfMonth();
Integer startMonth = new WxDateTime(times.get(0)).getMonthOfYear();
fetchDaily = networkService.getAlmanacDailyObservable(WxLocation.latLngStr(location), apiKey, unit.id, format, startDay, startMonth);
```

`getAlmanacDailyObservable` genuinely takes `day`/`month` and sends them as real query
params (`START_DAY`/`START_MONTH` in `WeatherNetworkServiceImpl`) - unlike the historical
endpoint, `setTime()` here actually matters. But note what's missing: **no year**, in
either the request or the response (`WxAlmanacDaily.almanacRecordDate` is `MMDD` only).
`WxAlmanacDaily.getSample()` maps `sample.highTemperature`/`lowTemperature` from
`temperatureAverageMax`/`temperatureAverageMin` - the **10-30 year NCDC climate normal**
for that calendar day, not last year's actual observations. A year-ago date and today's
date land on (almost) the same calendar day, so this is a reasonable stand-in for "vs. last
year" in practice, but it is a genuinely different statistic - normal vs. actual.

`WxDataWeatherProvider.getHistoricalDailyAverage()` now uses `WxAlmanacDailyFetcher`:
fetches its (always 30-day) response starting at `startMillis`, then filters entries down
to the requested window by calendar month/day (year-independent, since the response has no
year) and averages `temperatureAverageMax`/`temperatureAverageMin` over the matches.
`MainActivity`'s "LstYr max/min" label switches to "Normal max/min" when
`WeatherSourceConfig.getActiveSource() == WXDATA`, so the UI stays honest about which
statistic is actually showing per source (`lyMaxLabelText`/`lyMinLabelText` ids added to
all three `activity_main.xml` layout variants for this).

`WxHourlyHistoricalFetcher` (`getHourly1DayHistoricalObservable`) has the same
no-date-param shape as the daily historical fetcher, trailing 1-day-from-now - not wired up,
since nothing in `WeatherProvider` currently needs hourly historical data.

## 4. Threading — resolved

wxdata is now `CompletableFuture`-based throughout (no RxJava, no Retrofit).
`BaseWxWeatherBasedFetcher.getFetchFuture()` is public on each concrete fetcher and
self-starts the fetch on demand (`if (loadFuture == null || loadFuture.isDone()) doFetch()`),
so a fresh `WxCurrentFetcher()`/`WxHourlyFetcher()`/`WxDailyFetcher()` per `WeatherProvider`
call, with `.getFetchFuture().get(timeout, unit)` blocking on it, gives exactly the one-shot
fetch shape HomeWx wants — no `startAutoFetch()` ever called, matching
`OpenMeteoWeatherProvider`'s plain-blocking pattern under `Dispatchers.IO`.

`WxData.initialize()` happens once, in `HomeWxApp.onCreate()` (unconditional — cheap and
idempotent regardless of which source is active). This AAR build has `C.USE_PROVISIONING =
false` baked in, so `initialize(context, apiKey, listener, overrideConfig)` with
`overrideConfig = mapOf(WxData.OVER_SUN to sunApiKey)` resolves synchronously, no network
round trip — unlike the sample app's `WxDataHelper`, which goes through the full
provisioning service with a different kind of key (a "Widget API key", not the raw SUN key).
By the time any fetcher is constructed later, `WxData` is already initialized and each new
fetcher's constructor picks up the network service immediately via wxdata's own sticky
event bus.

## 5. Icon codes — resolved, no table needed

wxdata's `iconCode` (0-46, `WxCurrentConditions.iconCode` / per-hour `WxHourlyForecast
.iconCode[i]` / per-daypart `WxDailyForecast.DayPart.iconCode[i]`) turned out to already
*be* the canonical TWC "wx-icons.csv IconCode" numbering — the same one HomeWx's own
`wx_sun_NNd`/`wx_sun_NNn` drawable names were sourced from in the first place (confirmed by
cross-checking `OpenMeteoWeatherProvider.weatherCodeToIconKey`'s *output* numbers, e.g. 32
for sunny, 26 for cloudy, against wxdata's doc comments for the same conditions). So there's
no lookup table at all — just `"wx_sun_%02d%s".format(iconCode, if (dayOrNight == "N") "n"
else "d")`. Codes with no matching drawable (only 40 of the ~47 exist under
`res/drawable-nodpi/`) already fall back to "Not Available" (44) inside
`weatherIconRes()` (`ui/WeatherIconResolver.kt`), same as any other source.

## 6. Daily wind-max / day-vs-night selection — resolved via `getSample()`

Turned out to be a non-issue: `WxHourlyForecast.getSample(hour)` and
`WxDailyForecast.getSample(day, nightPart)` are both public library helpers that already
return a uniform `WxWeatherSample` (temperature/highTemperature/lowTemperature/windSpeed/
precipPercent/iconCode/...) with the day-vs-night daypart indexing and the "favor day, fall
back to night" logic already done *inside* the library. No need to hand-parse the parallel
`DayPart` arrays or resolve a wind-max fallback ourselves — `sample.windSpeed` on the daily
sample already is that day's representative wind reading.

## 7. Settings integration — done

- `weather/WeatherSourceId.kt` — added `WXDATA`.
- `weather/WeatherProviderFactory.kt` — added `WeatherSourceId.WXDATA -> WxDataWeatherProvider()`.
- `weather/wxdata/WxDataWeatherProvider.kt` — new, implements `WeatherProvider`.
- `HomeWxApp.onCreate()` — calls `WxDataWeatherProvider.initialize(this)`.
- `SettingsActivity`'s `weatherSourceSpinner` needed zero changes — it already derives its
  options from `WeatherSourceId.values()` and auto-enables once there's more than one.

## 8. Gradle wiring — done, with one surprise

- `app/build.gradle.kts`: `SUN_API_KEY` build config field (mirrors `GOVEE_API_KEY`);
  `implementation(files("libs/WxData-debug-2.26.0903.aar"))`; plus
  `com.squareup.okhttp3:logging-interceptor`, `com.google.code.gson:gson:2.14.0`,
  `org.greenrobot:eventbus:3.3.1` explicitly — a local-file AAR carries no dependency
  metadata of its own, so *every* one of wxdata's own deps needs restating here, even the
  ones it marks `implementation` rather than `api` (confirmed against wxdata's own
  `dependencies.gradle`). `androidx.lifecycle:lifecycle-runtime:2.11.0` was also needed but
  is already satisfied transitively by HomeWx's existing `lifecycle-runtime-ktx:2.11.0`.
- **Surprise**: wxdata's AAR manifest declares `minSdkVersion 28`; HomeWx was `minSdk = 24`.
  Manifest merge failed until HomeWx's `minSdk` was raised to 28 (user's explicit choice
  over `tools:overrideLibrary`, to avoid a "may lead to runtime failures" override on an
  unverified API-level assumption). This drops HomeWx's own support for Android 7.0-7.1
  devices, if any matter here.

## 9. Open items

- [ ] Not yet device-tested — only `compileDebugKotlin`/`assembleDebug` have been run.
- [ ] Consider whether "Normal max/min" (WXDATA) vs. "LstYr max/min" (Open-Meteo) reads
  clearly enough in the actual UI, now that the same slot means two different statistics
  depending on active source (§3) - a device look, not a code question.
- [x] Production API key — resolved: `SUN_API_KEY` in `local.properties` (§1).
- [x] Release AAR — resolved: use the debug AAR for both build types for now (§2).
- [x] Historical daily average — resolved via `WxAlmanacDailyFetcher` (climate normals, not
  last year's actuals - `WxDailyHistoricalFetcher`'s constructor access turned out to be a
  dead end for this specific feature, see §3).
- [x] Threading/call shape — resolved: `CompletableFuture` + blocking `.get()` (§4).
- [x] Icon-code mapping — resolved: no table needed, same numbering as HomeWx's drawables (§5).
- [x] Daily wind-max / day-night selection — resolved: `getSample()` already does it (§6).
- [x] Gradle transitive deps — resolved (§8).
