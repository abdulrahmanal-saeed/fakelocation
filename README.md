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

**v1.8.0:** A navigation voice you hear over loud music: it pauses (or lowers) whatever is playing while it talks,
turns the volume up for the sentence and back after, and boosts the voice itself (Settings → Lanes & voice → Voice
volume, or long-press the speaker button while driving). "Voice over Bluetooth call" plays directions like a phone
call so most car stereos cut the radio.

**v1.8.1:** Groundwork. Every build runs the unit tests first, and only the default branch replaces "Latest build",
so a test build from another branch never lands on the phone. All internet calls go through one helper: a server
that can't be reached is tried once more, and with no connection at all search and routes say "No internet
connection" instead of a general error. Fixed a brief false "Search needs internet" while typing, and a false
"Couldn't get a route" when the route was recalculated. Failures that used to be silent (Google falling back,
speed limits not loading, GPS not starting) are now written to the log.

**v1.9.0:** Settings reworked. The main screen is a setup check (mock location app, location, notifications,
full-screen alarms, battery: each with a Fix button that opens the right Android setting) and a short list of pages
(Alerts, Fake movement, Lanes & voice, Car & costs, Google Maps, Map & routing, Backup & restore); each page has
"Reset this page to defaults". Backup & restore keeps favorites, history, routes and settings in one file (the
Google key only if you say so); restoring can add to what's on the phone or replace everything. Map & routing takes
your own OSRM and Valhalla servers, tried before the public ones and checked when you save them; when every OSRM
server fails, routes come from Valhalla instead, for fake trips and for navigation.

**v2.0.0:** More ways to move the fake location.
- **Trip speed** 0.5× to 3× in Timing, and changeable during a trip (tap the speed on the map); the trip carries on
  from the same spot.
- **There and back** and **times in a row** (up to 10×) for trips that end where they start.
- **Weekly routes:** pick days under "Start at" / "Arrive by" and save the schedule. When it's due, the phone shows a
  full-screen "is due" notice that starts the trip (on its own when the screen is off; tap Start while you're using
  the phone, since Android only lets location services start from the foreground). Survives reboots.
- **Joystick** (Move → Move with joystick): walk, run or drive the fake position by hand; Done names the new spot.
- **Wander around here** (Move): a walk along real streets to random spots within 200 m – 1 km, pausing at each,
  for 30 min – 2 h, and back.
- **GPX / KML:** Import on the Routes screen (Strava, Google My Maps, Garmin… recorded tracks keep their shape and
  speed); long-press a route → Export as GPX.
- **Shortcuts:** long-press the app icon to go to one of your first three favorites, or Stop.

**v2.1.0:** Better real navigation.
- **Other ways:** before starting, up to three routes as cards (time, distance, Salik + fuel, "+4 min") and grey lines
  on the map; tap either to pick one.
- **Home / Work** buttons on the Navigate screen (first tap or long-press sets them), in Android Auto's saved places,
  and as launcher shortcuts ("Navigate home").
- **Night map:** darker map colours after sunset (worked out from where you are), or always / never
  (Settings → Map & routing). The car screen follows the car's own day/night.
- **Drive log** (Settings → Drive log): every navigation that went somewhere, with distance, time, average speed,
  Salik / Darb passed and fuel, added up per month.
- **Share arrival time** button while driving: "On my way to …, arriving around 18:42" plus a map link, to WhatsApp,
  SMS or anything else.
- **Parking on arrival:** arriving by car shows the nearest parking; tap to drive on to it.

**v2.2.0:** Look and feel.
- **Dark theme** that follows the phone, or always light / dark (Settings → About → Theme). The street map darkens with
  it; satellite pictures, alarms and widgets keep their own colours.
- **Undo** for a deleted favorite, a deleted route (long-press → Delete) and a cleared history.
- **Drag favorites** by the handle to put them in order: the first ones are the map's chips, the widget and the
  shortcuts.
- **Favorites widget:** your first four favorites as one-tap buttons on the home screen, plus Stop.
- **What's new** after an update (and in Settings → About), a tick under your finger when you teleport, start or stop,
  and labels for every icon-only button.

**v2.3.0:** Under the hood.
- **Update check:** once a day (and Settings → About → Check for updates) the app looks at "Latest build" and offers
  the new APK when there is one.
- **Smaller APK:** each build also publishes `FakeLocation-release.apk`, the same app shrunk with R8 (about 4 MB
  instead of 12 MB, same signing key, so it installs over the other one either way). `FakeLocation.apk` stays the
  main download until the small one has been tried on the phone.
- Code split up: the fake-location service's notifications and alarms, and the Navigate screen's map drawing, are now
  in their own files.

**v2.4.0:** Smoother driving.
- The car is drawn on every screen frame: between GPS fixes it carries on along the road at your speed and each new
  fix is blended in, so it no longer lags a second behind, stops and starts, or cuts across corners.
- The map turns smoothly (no shaking with the GPS heading) and the zoom glides: further out on fast roads, and out just
  enough while a turn comes up that you see it before you reach it.
- **Tunnels:** when the GPS goes quiet for 3 seconds the position is estimated along the route at the speed you had
  (for up to 90 seconds / 2.5 km), so the car, distances, arrival time and voice keep going; the banner says "No GPS
  signal".
- Moved the map while driving? It goes back to following the car by itself after 10 seconds.

See [PLAN.md](PLAN.md) for the full feature list.

## Install

1. Open the repo's **Releases** → **Latest build** and download `FakeLocation.apk` on the phone.
2. Allow "Install unknown apps" for your browser/files app when asked.

Later builds: the app offers them itself (once a day, or Settings → About → Check for updates).

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
