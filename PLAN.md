# Fake Location – Plan

Personal Android app (Samsung A15, Android 16). English UI. OpenStreetMap now, Google Maps as an option later.

Design (all screens, logo, splash): https://claude.ai/artifact/9FMXf1izujP79r3BAWNCKW

## Features

### Core
- Teleport: long-press the map, search, coordinates, or a shared Google Maps link
- "My location" button that remembers the real position while faking is on
- Routes on real streets (OSRM), car or walk, moving at each street's speed
- Wait time at every stop, including the first one before leaving
- Alarm 2 minutes before reaching each stop

### Added
- Natural movement: speed varies, slows at turns, small GPS drift
- "Arrive by" timing: pick the arrival time, the app works out the start time (also "Start now" / "Start at")
- Alarm before a wait ends (default 1 minute)
- Alarms ring even in silent mode (full-screen alarm)
- Quick Settings tile (on/off)
- Home-screen widget with trip status
- Offline maps: every area you view is kept on the phone (size limit in Settings). Downloading whole areas in
  advance isn't allowed by OpenStreetMap's free tile servers, so it isn't offered.
- Favorite places
- Place history
- Enter coordinates directly
- Receive a link shared from Google Maps

## Phases

| # | Scope | Status |
|---|-------|--------|
| 0 | UI/UX design: logo, splash, every screen | Done – approved |
| 1 | Engine: fake location service, map, teleport, real-location button | Done (v0.1.0) |
| 2 | New look: splash, setup, bottom navigation, teleport sheet, search, coordinates, Google Maps links, favorites, history | Done (v0.2.0) |
| 3 | Route editor, street routing, timing (start now / start at / arrive by), saved routes | Done (v0.3.0) |
| 4 | Trip engine: movement at street speed, natural movement, waits, alarms (before stop, before wait ends, silent mode) | Done (v0.4.0) |
| 5 | Quick Settings tile, widget, offline maps | Done (v1.0.0) |
| 6 | Google Maps option (tiles, routes, search) with an in-app API key | Done (v1.1.0) |
| 7 | Real navigation on the real GPS while the fake location keeps running | Done (v1.2.0) |
| 8 | Driving screen: lanes, speed limit vs. my speed, speed cameras | Done (v1.3.0) |
| 9 | Android Auto, nearby categories (fuel, parking, bus, metro, EV), UAE defaults, navigation with the screen off | Done (v1.4.0) |
