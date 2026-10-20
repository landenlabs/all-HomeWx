# Google Nest thermostat setup (SDM API)

HomeWx reads the thermostat's temperature and furnace run state through Google's
Smart Device Management (SDM) API, also called Device Access.
Client code: `app/src/main/java/com/dlang/homewx/data/NestApiClient.kt`.

Credentials go in `local.properties` (gitignored) and reach the app as `BuildConfig` fields:

```
NEST_PROJECT_ID=...
NEST_CLIENT_ID=...
NEST_CLIENT_SECRET=...
NEST_REFRESH_TOKEN=...
```

## 1. Device Access project (gives `NEST_PROJECT_ID`)

1. Go to https://console.nest.google.com/device-access and accept the terms.
   There is a one-time $5 registration fee.
2. Create a project. Skip or fill in the OAuth client ID when asked (you can add it later).
3. Copy the **Project ID** (a UUID). This is `NEST_PROJECT_ID`.

## 2. Google Cloud OAuth client (gives `NEST_CLIENT_ID`, `NEST_CLIENT_SECRET`)

1. In https://console.cloud.google.com, create or select a project.
2. Enable the **Smart Device Management API**.
3. Configure the OAuth consent screen. Set publishing status to **In production**.
   In "Testing" status refresh tokens expire after 7 days.
4. Create credentials > OAuth client ID > **Web application**.
   Add `https://www.google.com` as an authorized redirect URI.
5. Copy the client ID and client secret into `local.properties`.
6. Back in the Device Access console, set the project's OAuth client ID to this client ID.

## 3. Authorize your home (gives the authorization code)

Open this URL in a browser, replacing `PROJECT_ID` and `CLIENT_ID`:

```
https://nestservices.google.com/partnerconnections/PROJECT_ID/auth?redirect_uri=https://www.google.com&access_type=offline&prompt=consent&client_id=CLIENT_ID&response_type=code&scope=https://www.googleapis.com/auth/sdm.service
```

1. Sign in with the Google account that owns the thermostat's home.
2. Select the home and thermostat, and grant access.
3. You land on a google.com page. Copy the `code=` value from the address bar
   (stop before the next `&`). It is single-use and short-lived.

## 4. Exchange the code for a refresh token (gives `NEST_REFRESH_TOKEN`)

```
curl -X POST https://www.googleapis.com/oauth2/v4/token \
  -d client_id=CLIENT_ID \
  -d client_secret=CLIENT_SECRET \
  -d code=AUTH_CODE \
  -d grant_type=authorization_code \
  -d redirect_uri=https://www.google.com
```

Copy `refresh_token` from the response into `local.properties`.
The `access_token` in the response lasts one hour and is not needed; the app refreshes its own.

## 5. Verify

```
ACCESS=$(curl -s -X POST https://www.googleapis.com/oauth2/v4/token \
  -d client_id=CLIENT_ID -d client_secret=CLIENT_SECRET \
  -d refresh_token=REFRESH_TOKEN -d grant_type=refresh_token | jq -r .access_token)

curl -s -H "Authorization: Bearer $ACCESS" \
  https://smartdevicemanagement.googleapis.com/v1/enterprises/PROJECT_ID/devices | jq
```

The first `devices.list` call also completes the authorization.
You should see a device of type `sdm.devices.types.THERMOSTAT`.

## Response fields used

| Value | Field | Notes |
|---|---|---|
| Temperature | `sdm.devices.traits.Temperature.ambientTemperatureCelsius` | Celsius; client converts to °F |
| Furnace run state | `sdm.devices.traits.ThermostatHvac.status` | `OFF`, `HEATING`, `COOLING` |
| Thermostat mode | `sdm.devices.traits.ThermostatMode.mode` | `HEAT`, `COOL`, `HEATCOOL`, `OFF` |
| Setpoints | `sdm.devices.traits.ThermostatTemperatureSetpoint.heatCelsius` / `coolCelsius` | Celsius |
| Online | `sdm.devices.traits.Connectivity.status` | `ONLINE` / `OFFLINE` |

A missing trait means it is currently unavailable. HVAC status events can arrive
later than mode changes, since equipment often delays start-up.

## Gotchas

- Refresh tokens expire after 6 months without use, and after 7 days if the OAuth app is in "Testing" status.
- SDM API quota is limited (about 5 requests/minute per user), so poll every few minutes at most.
- Never commit the client secret or refresh token. Keep them only in `local.properties`.

## Remaining app work

- Add a `NestRepository` and poll it from `HomeWxMonitorService`.
- Show temperature and furnace state in the UI (location not decided yet).

## References

- https://developers.google.com/nest/device-access/authorize
- https://developers.google.com/nest/device-access/api/thermostat
- https://developers.google.com/nest/device-access/traits/device/thermostat-hvac
