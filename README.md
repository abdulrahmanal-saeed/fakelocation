# Fake Location

Personal Android app that fakes the phone's GPS location, with route simulation planned.

## Status

**Phase 1 (done):** map (OpenStreetMap), place search, long-press → teleport, "My location" button
that remembers the real position while faking, background service with Stop in the notification.

Next phases: route editor on real streets (OSRM), wait time at each point, movement at street speed,
alert 2 minutes before each point, saved routes.

## Install

1. Open the repo's **Releases** → **Latest build** and download `FakeLocation.apk` on the phone.
2. Allow "Install unknown apps" for your browser/files app when asked.

## One-time phone setup

1. Settings → About phone → Software information → tap **Build number** 7 times.
2. Settings → Developer options → **Select mock location app** → *Fake Location*.
3. Open the app and allow location + notifications.

## Use

- Long-press anywhere on the map → **Teleport here**.
- Or search for a place at the top.
- **Stop** (button or notification) returns the phone to its real GPS.

## Build locally

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
