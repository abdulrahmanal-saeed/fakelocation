# Fake Location

Personal Android app that fakes the phone's GPS location, with route simulation planned.

## Status

**v0.2.0:** new design (logo, splash, first-run setup, bottom navigation), long-press / search / coordinates /
Google Maps links → teleport sheet, favorites, history, "My location" button that remembers the real position
while faking, background service with Stop in the notification.

**v0.3.0:** routes. Add stops (search, long-press the map, or "Add to route" on any place), set the wait at each
stop, choose car or walk, and the path is calculated on real streets with each street's travel time. Then pick
the timing: start now, start at a time, or arrive by a time (the app works out when to leave).

**v0.4.0:** trips run. The fake position moves along the route at each street's speed (with natural speed
changes, slowing at turns and a little GPS drift), waits at every stop, and alarms ring 2 minutes before each
stop and 1 minute before a wait ends, even in silent mode. Pause, skip, +5 min and leave now from the map or the
notification. Scheduled trips ("start at" / "arrive by") start by themselves.

**v1.0.0:** Quick Settings tile (on = stop, off = go to your first favorite), home-screen widget with the trip
status, a live countdown and buttons, and offline maps: areas you've viewed stay on the phone (limit and clear in
Settings), with an "offline" banner on the map. Saved routes run without internet.

**v1.1.0:** Google Maps as an option. Paste your own Google API key in Settings → Google Maps, then choose
Google for the map (normal, satellite, satellite + roads), for routes (with live traffic) and/or for search.
"Test key" checks each Google API and shows Google's message if one isn't enabled. Anything Google can't do
falls back to the free OpenStreetMap services.

Google Cloud setup: enable Map Tiles API, Routes API, Places API (New) and Geocoding API, create an API key,
and (recommended) restrict it to Android apps with package `com.abdulrahman.fakelocation` and SHA-1
`26:3F:24:75:BE:D1:E6:B5:02:49:80:07:17:F1:F9:B2:EE:25:3F:FD` (the app's signing key in `keystore/`).

See [PLAN.md](PLAN.md) for the full feature list.

## Install

1. Open the repo's **Releases** → **Latest build** and download `FakeLocation.apk` on the phone.
2. Allow "Install unknown apps" for your browser/files app when asked.

## One-time phone setup

1. Settings → About phone → Software information → tap **Build number** 7 times.
2. Settings → Developer options → **Select mock location app** → *Fake Location*.
3. Open the app and allow location + notifications.

## Use

- Long-press anywhere on the map → **Teleport here**.
- Or search a place, type coordinates, or paste a Google Maps link at the top.
- In Google Maps: **Share** a place → **Fake Location** to open it straight in the app.
- Star a place to keep it in **Places → Favorites**; every teleport is listed in **History**.
- **Stop** (button or notification) returns the phone to its real GPS.

## Build locally

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
