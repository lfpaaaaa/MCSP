#!/usr/bin/env python3
"""Create one disposable timetable occurrence for the context-sensing rehearsal."""

import argparse
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4
from zoneinfo import ZoneInfo


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--start", help="Class start in ISO format; local times use Australia/Melbourne")
    parser.add_argument("--duration-minutes", type=int, default=60)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.duration_minutes <= 0:
        parser.error("--duration-minutes must be positive")

    zone = ZoneInfo("Australia/Melbourne")
    now = datetime.now(zone)
    try:
        start = datetime.fromisoformat(args.start) if args.start else (
            now.replace(second=0, microsecond=0) + timedelta(minutes=35)
        )
    except ValueError:
        parser.error("--start must be an ISO date and time, for example 2026-10-13T15:00")
    if start.tzinfo is None:
        start = start.replace(tzinfo=zone)
    start = start.astimezone(zone)
    end = start + timedelta(minutes=args.duration_minutes)

    def utc(value):
        return value.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    lines = [
        "BEGIN:VCALENDAR", "VERSION:2.0",
        "PRODID:-//Campus Companion//Context Rehearsal//EN",
        "BEGIN:VEVENT", f"UID:context-rehearsal-{uuid4()}", f"DTSTAMP:{utc(now)}",
        f"DTSTART:{utc(start)}", f"DTEND:{utc(end)}",
        "SUMMARY:COMP90018 Mobile Computing Systems Programming Lecture",
        "DESCRIPTION:Activity: lecture",
        r"LOCATION:Parkville\, PAR-160", "END:VEVENT", "END:VCALENDAR",
    ]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(("\r\n".join(lines) + "\r\n").encode("utf-8"))
    print(f"Saved {args.output}; class {start.isoformat()} to {end.isoformat()}")


if __name__ == "__main__":
    main()
