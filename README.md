# Fake Location

Personal Android app that fakes the phone's GPS location, with route simulation planned.

## Status

**v0.2.0:** new design (logo, splash, first-run setup, bottom navigation), long-press / search / coordinates /
Google Maps links → teleport sheet, favorites, history, "My location" button that remembers the real position
while faking, background service with Stop in the notification.

**v0.3.0:** routes. Add stops (search, long-press the map, or "Add to route" on any place), set the wait at each
stop, choose car or walk, and the path is calculated on real streets with each street's travel time. Then pick
the timing: start now, start at a time, or arrive by a time (the app works out when to leave).

Next: the trip engine (moving along the route, waiting, alarms). See [PLAN.md](PLAN.md).

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
