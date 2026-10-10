# Automatic timetable groups

After a successful calendar import, the signed-in client calls `sync_timetable_groups` with course and class matching fields. No private calendar URL or event UID is uploaded. The database inserts or reuses each shared group and adds only the calling user as a member. Other students join the same UUID after importing their own calendars. Imports are additive and idempotent; old groups remain as folded history.

- Course-wide group: course code.
- Small class: course code, tutorial/workshop type, weekday, start/end time in Australia/Melbourne, normalized full location including room.
- Small group titles use the course code and activity, for example `COMP90018-tutorial` or `COMP90018-workshop`.
- Activity is read from calendar summary suffix/prefix or explicit activity/class type fields in the description. Explicit course/subject names in the description take precedence.
- Weekly occurrences, event UIDs, device time zones and daylight saving do not create duplicate groups. Unknown locations, all-day events and missing durations do not generate guessed small groups.
- Slot labels in the group list distinguish same-named groups. Small groups fold when their own matching sessions end, independently of lectures or other rooms.
- System-managed groups do not assign ownership to the first student. Existing manually created groups and QR/NFC joining are unchanged.
- Matching retains the existing course-code lifetime; it does not infer semester cohorts from dates. Location normalization handles case, Unicode width, whitespace and comma spacing, not building aliases.

Apply `20261010030000_timetable_groups.sql` to each Supabase environment before using the new client. The local emulator database has been migrated. The app keeps schedules available while group sync retries after a network/server error.

Validation: parser fixtures cover lecture/tutorial/workshop naming, recurring duplicates, time/room differences, missing location, all-day events, daylight saving and device time zones. PostgreSQL rollback tests cover cross-account membership, RLS, idempotence and invalid matching input. No live student calendars are needed for these tests.
