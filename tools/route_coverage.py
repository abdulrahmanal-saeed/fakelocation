"""Probe public test routes and optionally measure HERE posted-limit coverage.

Run with Python 3. Set HERE_API_KEY in the environment for the HERE column.
This probe uses fixed public landmarks and writes CSV to standard output.
"""
import csv
import json
import os
import sys
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROUTES = (
    ("Cairo", "city", (30.0444, 31.2357), (30.0626, 31.2497)),
    ("Cairo", "ring road", (30.0151, 31.4087), (30.0641, 31.4013)),
    ("Alexandria", "coastal road", (31.2089, 29.9092), (31.2684, 30.0057)),
    ("Dubai", "city", (25.2048, 55.2708), (25.1975, 55.2790)),
    ("Dubai", "Sheikh Zayed Road", (25.1181, 55.2006), (25.2164, 55.2798)),
    ("Abu Dhabi", "city", (24.4539, 54.3773), (24.4766, 54.3692)),
    ("Abu Dhabi", "Sheikh Zayed Bridge", (24.4131, 54.4742), (24.4278, 54.5506)),
    ("Riyadh", "city", (24.6877, 46.6850), (24.7118, 46.6740)),
    ("London", "city", (51.5014, -0.1419), (51.5154, -0.1418)),
    ("New York", "city", (40.7484, -73.9857), (40.7580, -73.9855)),
)


def read_json(url, data=None):
    headers = {"User-Agent": "WayShift route coverage (github.com/abdulrahmanal-saeed/fakelocation)"}
    if data is not None:
        headers.update({"Content-Type": "application/json", "X-Client-Id": "WayShift"})
        data = json.dumps(data).encode()
    try:
        with urlopen(Request(url, data=data, headers=headers), timeout=8) as response:
            return json.load(response), "ok"
    except HTTPError as error:
        return {}, f"HTTP {error.code}"
    except (OSError, ValueError) as error:
        return {}, type(error).__name__


def valhalla(points, language):
    data, status = read_json("https://valhalla1.openstreetmap.de/route", {
        "locations": [{"lat": lat, "lon": lon} for lat, lon in points],
        "costing": "auto", "language": language, "units": "kilometers",
    })
    if "trip" in data:
        return data["trip"].get("language", "language missing"), data["trip"]["summary"]["length"]
    return status, ""


def osrm(points):
    coordinates = ";".join(f"{lon},{lat}" for lat, lon in points)
    data, status = read_json("https://routing.openstreetmap.de/routed-car/route/v1/driving/"
                             + coordinates + "?overview=false")
    return (round(data["routes"][0]["distance"] / 1000, 2), "ok") if data.get("routes") else ("", status)


def here(points, api_key):
    if not api_key:
        return "", "HERE_API_KEY unavailable"
    origin, destination = (f"{lat},{lon}" for lat, lon in points)
    query = urlencode({"apiKey": api_key, "transportMode": "car", "origin": origin,
                       "destination": destination, "return": "polyline,summary", "spans": "length,maxSpeed"})
    data, status = read_json("https://router.hereapi.com/v8/routes?" + query)
    if not data.get("routes"):
        return "", status if status != "ok" else "no route"
    sections = data["routes"][0]["sections"]
    lengths = sum(section["summary"]["length"] for section in sections)
    mapped = sum(span.get("length", 0) for section in sections for span in section.get("spans", [])
                 if isinstance(span.get("maxSpeed"), (int, float)) and span["maxSpeed"] > 0)
    return round(mapped * 100 / lengths, 1) if lengths else "", "verify against signs"


def main():
    writer = csv.writer(sys.stdout)
    writer.writerow(("city", "sample", "direction", "Valhalla language/status", "Valhalla km",
                     "OSRM km", "OSRM status", "HERE distance with a numeric limit (%)", "HERE status"))
    key = os.environ.get("HERE_API_KEY")
    for city, route, start, end in ROUTES:
        directions = (("outbound", (start, end)),)
        if city in {"Cairo", "Alexandria", "Dubai", "Abu Dhabi"}:
            directions += (("return", (end, start)),)
        for direction, points in directions:
            language = "ar-SA" if city not in {"London", "New York"} else "en-US"
            writer.writerow((city, route, direction, *valhalla(points, language), *osrm(points), *here(points, key)))
            sys.stdout.flush()
            time.sleep(1.1)


if __name__ == "__main__":
    main()
