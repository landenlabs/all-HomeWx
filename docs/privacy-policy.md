---
# GitHub Pages (Jekyll) front matter: publishes this file as
# https://landenlabs.github.io/all-HomeWx/privacy-policy.html - the URL given to Google Play.
layout: default
title: All HomeWx Privacy Policy
permalink: /privacy-policy.html
---

# All HomeWx Privacy Policy

_Last updated: 2026-11-08_

All HomeWx ("the app") is published by LanDen Labs. This policy explains what the app does with data. The app has no user account, shows no ads, does not use an advertising ID and does not sell or share data for marketing.

## Data stored only on your device
Settings, your home zip code, sensor history, weather, river and rain history and error logs are stored locally on your tablet. You can export and import this data yourself from Settings; the export file goes where you choose to save it and never includes credentials. We do not receive it.

If you enter Govee, Wyze or WxData credentials, they are stored on the tablet only, encrypted with the Android Keystore, and are used only to sign in to that service.

If Android backup is turned on for your tablet, Android may back up the app's settings (including the zip code) to your Google account. Saved credentials can't be decrypted after a restore.

## Location
The app does not ask for location permission and never reads your device's GPS or network position. Weather, rain, river and radar data are fetched for the center point of the zip code you enter in Settings, and the "home" marker on the radar map is placed at that same point.

## Data sent to third parties
To show you content, the app sends requests to the services below. Those requests necessarily include your IP address and, for weather, rain and river data, the coordinates of your zip code's center point. Each service has its own privacy policy.
- Weather: Open-Meteo, and WxData (weather.com) in builds that include it, if you enter a key
- Zip code lookup: zippopotam.us
- Rivers: USGS Water Data
- Radar, maps, rain and drought: Iowa Environmental Mesonet, NASA GIBS, Esri ArcGIS, NOAA, ACIS, US Drought Monitor
- Market overview: TradingView
- News and article viewer: the RSS feeds and web pages you configure. These sites may set cookies in the app's web view.
- Optional, only if you add your own credentials: Govee (indoor sensors) and Wyze (camera sign-in page)

## Analytics and crash reports
The app uses Google Firebase Analytics and Crashlytics, on by default, to learn which features are used and to fix crashes. They collect app usage events, crash stack traces, device model, OS and app version, country and a random app-instance identifier. They do not collect your name, email or contact information. An error report can include a Govee sensor's device name or a server's error message. See https://firebase.google.com/support/privacy.

You can turn this off in **Settings > Privacy > Send anonymous usage stats and crash reports**. Turning it off stops all further reporting, discards crash reports that have not been sent yet and resets the analytics identifier.

## Permissions
- Internet and network state: to fetch data and detect when the network is down
- Foreground service (data sync) and notifications: keeps updates running while the dashboard is on, shown by an ongoing notification
- Run at startup: reopens the dashboard after the tablet restarts
- Wake lock and Play install referrer: added by Firebase Analytics

The app does not use location, camera, microphone, contacts, photos or storage permissions. A background-image folder is read only if you choose one in Settings.

## Children
The app is not directed to children under 13.

## Retention and deletion
Data on your device is removed when you clear the app's storage or uninstall it. Firebase deletes crash reports after 90 days and analytics data after 2 months. Data already sent to Firebase can't be deleted from within the app.

## Changes
We will update this page when the policy changes and revise the date above.

## Contact
<!-- TODO: replace with a real contact address before publishing -->
Email: landenlabs.app@gmail.com
