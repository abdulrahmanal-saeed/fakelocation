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

**v1.2.0:** real navigation, like Waze, on top of the fake location. The blue button on the map (or "Navigate
here" on any place) gives turn-by-turn directions with voice for where you REALLY are, while the fake location or
fake trip keeps running for other apps. During navigation the raw GPS chip is left real so the app can follow you;
Google's location service (used by Google Maps and most apps) keeps reporting the fake position. Going off the
route finds a new way automatically.

**v1.3.0:** Yango-style driving screen: lane arrows at junctions (bright = lanes to use), your speed next to the
road's speed limit (turns red and beeps when you're over), speed-camera warnings, current street name, green route
with a car icon, and a bottom bar with distance, arrival time, minutes and progress. Lanes, limits and cameras come
from OpenStreetMap, so they show only where they are mapped (lanes are rare in Egypt today); with Google routes
there are no lane arrows.

**v1.4.0:** Android Auto: the car screen shows the map, search, saved places and nearby categories, a route
preview and turn-by-turn guidance with lane arrows; voice, speed limits and cameras come from the phone. Navigation
now keeps going with the phone screen off. Nearby search (Fuel, Parking, Bus stops, Metro, EV charging) on the phone
and in the car, through Google Places when a key is set, otherwise OpenStreetMap. Search and Google results are
biased to the UAE (Dubai by default).

**v1.5.0:** Salik (Dubai) and Darb (Abu Dhabi) gates: the route preview shows how much the trip costs in tolls
(priced for the time you'll pass each gate) plus fuel, and the voice warns before each gate. "No tolls" and
"No highways" route options (Google, or Valhalla on OpenStreetMap). Several stops in one real navigation, also
added while driving (phone "+" or the car's "+" → Nearby). A Parking screen (P button on the map): zone code from
the sign, pay by SMS to 7275, countdown, an alarm before the time runs out, and walking directions back to the car.
Fuel price per litre, grade and consumption are set in Settings → Car & costs (built in: October 2026 prices).

**v1.6.0:** Driving screen reworked after a real drive: the car glides with the GPS and sits low on a heading-up map,
the green line starts at the car and shrinks as you drive, a compact turn card with a "Then" pill, lane arrows in a
bubble at the junction (from OpenStreetMap turn lanes, so they also work with Google routes), a white turn arrow on
the road, live traffic (Google) in yellow/red on the route and on the bottom bar, speed limits loaded 20 km at a time
ahead of you, and a clearer voice that speaks earlier at higher speeds and lowers your music while it talks.

**v1.7.0:** Lane guidance (like Amap, without HD maps): a lane strip shows how many lanes the road has (from
OpenStreetMap), which one you're in and, before a turn, which lanes to take, with "Move 2 lanes right" on screen and
by voice. Your lane is estimated from turns (a right turn puts you in the right lane), lane changes seen by the
gyroscope against the road's direction, an optional lane camera (back camera in a dashboard holder: lane lines and the
yellow edge line) and metre-accurate dual-frequency GPS when the phone has it. Voice and speaking speed can be chosen
in Settings → Lanes & voice.

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

## Android Auto

The app isn't from the Play Store, so Android Auto hides it until you allow unknown sources once:

1. Open the **Android Auto** app settings on the phone (Settings → Connected devices → Android Auto).
2. Scroll to the bottom and tap **Version** 10 times → **Developer settings** appears in the ⋮ menu.
3. Developer settings → turn on **Unknown sources**.
4. Connect to the car; **Fake Location** appears in the app launcher.

Open the app once on the phone first so it has location permission.

## Build locally

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
