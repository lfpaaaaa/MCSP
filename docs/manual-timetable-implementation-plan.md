**Manual timetable editing and venue refresh: implementation plan**

Prepared 12 October 2026 against `feat/homecard`, HEAD `9036ac9`. This is a proposed implementation; the application code has not been changed as part of this plan.

**1. Deliver the documented P0 requirement**

The supplied project plan §2.2 explicitly lists “timetable import and manual editing” as P0. Section 2.3 requires local database storage for class schedules, and §3 assigns ICS parsing into Room, the class/location model, manual editing, the context engine, Home and Schedule to the Context Engine & Product lead. Supabase is the collaboration backend. A local Room timetable editor therefore matches the written plan without requiring cloud timetable synchronization.

Recommended first release:

- Add a class without connecting a timetable URL.
- Edit or delete an individual imported or manually created occurrence.
- Restore an imported occurrence to its latest feed version; provide a way to restore a locally hidden imported occurrence.
- Persist changes across refresh, screen changes, process restart and offline use.
- Update Home, Schedule, departure reminders and navigation from the same saved timetable.
- Preserve the finished course's group updates during the engine's 15-minute window.

Single-occurrence editing is the initial scope. The form should say “Changes apply to this class on this date.” The plan does not explicitly require recurring-series editing. A later bounded “repeat weekly until” creation option can reduce repetitive entry, but “this and all future occurrences” needs an explicit series model and parser-preserved recurrence metadata. Course/activity reminder keys are not series identities. Richer sensor fusion, Activity Recognition and geofence integration remain separate work; the existing GPS and motion inputs are sufficient to validate the editor and destination correction.

**2. Current behavior and the implementation gaps**

| Existing code | Consequence for this work |
| --- | --- |
| `data/TimetableSubscriptionStore.kt` persists a per-account URL and JSON snapshot in SharedPreferences; `data/local/CampusDatabase.kt` contains only messages. | Timetable persistence exists, but timetable classes are not in the Room database specified by the project plan. |
| `data/TimetableSession.kt` replaces its complete timetable after a successful import. | Editing the current visible list would lose changes on the next refresh. |
| `ui/screens/HomeScreen.kt` automatically opens the URL dialog without a connected timetable and gates normal content on `isConnected`; Schedule has the same connection gate. | Manual-only accounts require explicit setup/data state and revised empty-state actions. |
| `data/AppRepositories.kt` restores the old subscription store directly into the application-owned travel engine after authentication. | UI-only editor wiring would leave background reminders using the old imported timetable after restart. |
| `context/TravelEngine.kt:128` resolves the building only when the tracked class ID changes. | A same-ID venue edit can publish the edited class together with the previous building, distance, ETA, weather and deadline. |
| `context/BuildingResolver.kt:20` tries room text before location text. | An imported room such as `PAR-160-B117` can override a newly selected building unless both fields are saved consistently. |
| `ui/model/HomeCard.kt:108` already checks ID, code, activity, times, location and room. | Home rejects an old session snapshot, but cannot detect a new session paired with an old destination inside the producer. |
| `context/TravelSnapshot.kt:52` enriches classes by ID alone; `ui/model/CourseReminderTiming.kt:25` checks ID and start alone. | Both consumers need the stronger occurrence comparison when edits become possible. |

All paths above are repository-relative. These findings come from the checked-out application code, rather than assumptions about the intended architecture.

**3. First implementation slice: correct destination changes before exposing the editor**

Start with a regression fixture: class X has a stable ID and is initially in building A. Change its building to B while keeping the ID and times. A recording ETA repository should return different durations for A and B; a recording weather repository should identify the requested destination. Assert that the building, distance, ETA requests, weather requests and leave-by deadline all change to B. This exercises the producer failure that a Home snapshot-mismatch test alone does not cover.

Implement these changes together:

- Resolve the current tracked venue on every refresh. Local building lookup is inexpensive, and unconditional resolution is the simplest initial correction. If caching remains, key it by venue identity rather than session ID.
- Extract the existing Home occurrence comparison into a shared helper. Compare stable ID, course/activity, start/end instants and location/room; exclude transient ETA and route fields. Use it in Home, `TravelSnapshot.applyTo()` and reminder timing previews.
- Reset context memory when the tracked occurrence or its physical destination (resolved building) changes. Keep it through room, title and time corrections within the same building: arrival is entered within 75 m and kept until 200 m, so a reset there turns indoor GPS error into a false "Running late" or "Time to leave" alert, and a changed start gets a fresh reminder-history key. A trip under way ends when the class moves out of its departure window (to another day, for example); the arrival is kept. Scope the rule in the pure engine as well as its caller (memory carries the building code; without one, only an unchanged occurrence keeps memory) so direct engine callers cannot retain arrival or distance history from another building.
- Reset destination-dependent en-route/geofence evidence when the destination changes or becomes unknown. Preserve current GPS position and global movement detection. A room-only change within the same building refreshes labels and keeps arrival (tested).
- Add an account/timetable generation to asynchronous work. Invalidate obsolete guidance immediately when a relevant edit or deletion reaches the engine; reject a superseded ETA/weather result before updating either decision memory or the published snapshot. Protect owner switches too. Cancellation may accompany this, but cancelling every request on every location tick could prevent results from completing.
- Reconcile departure notifications after material edits/deletions. Validate the current owner and occurrence before delivery; add occurrence-specific cancellation so removing one class does not clear unrelated reminders.
- Preserve notification spam prevention. For a venue-only correction, cancel or silently update an already visible old reminder; do not blindly reset all history and alert again. A changed start invalidates the previous schedule and can permit the appropriate warning for the new time. Specify and test both behaviors.
- Reconcile any separately selected in-app navigation destination. When its class is removed or moves building, stop obsolete route/get-off tracking and invalidate pending route results. A fresh Maps tap already resolves the current class, but an existing app-owned journey needs reconciliation. The app cannot automatically retarget an external Google Maps session already open.

Known-to-unknown venue changes must clear the destination, travel estimate, distance, weather allowance and deadline. Unknown-to-known changes must begin resolving normally even with the same ID. Existing route caches already include destination coordinates and mode, so this correction does not require flushing all cached routes.

Primary files: `context/TravelEngine.kt`, `ContextEngine.kt`, `TravelSnapshot.kt`, `BuildingResolver.kt`, `DepartureReminders.kt`; `ui/model/HomeCard.kt`, `CourseReminderTiming.kt`; `push/DepartureNotifier.kt`, `DepartureReminderHistory.kt`; `navigation/NavigationSession.kt`; the relevant destination-history handling in `sensing/service/SensingForegroundService.kt`.

**4. Second implementation slice: a Room repository with an imported baseline and local changes**

Use a dedicated `TimetableDatabase`. The existing `CampusDatabase` is documented as a rebuildable message cache and permits destructive migration. Manually entered timetable data is user-owned data that cannot be rebuilt from a server. A separate database gives it explicit schema and retention rules without changing chat-cache behavior. Existing Room/KSP dependencies are already present.

Suggested records:

| Record | Stored data and identity |
| --- | --- |
| `TimetableSourceEntity` | Owner ID, immutable feed-generation UUID, subscription URL, last successful sync time, source event count and import-derived group specs. |
| `ImportedSessionEntity` | Owner + feed generation + original imported occurrence ID; course/title/activity, location/room, start/end instants and academic zone ID. |
| `ImportedAdjustmentEntity` | Same source occurrence identity; explicit field overrides and a hidden/deleted flag. Empty values must be distinguishable from “no override.” |
| `ManualSessionEntity` | Owner ID + stable `manual:<UUID>`; the editable class fields and update metadata. |

Do not persist route estimates, ETA or context state as timetable facts. Compute the effective timetable as imported rows with local field changes applied, excluding hidden imported rows, plus manual rows. Sort by start instant and stable ID. Keep this merge testable as a pure function.

Repository operations should include observing the account's timetable, adding/updating/deleting a manual occurrence, overriding/hiding/restoring an imported occurrence, refreshing a source and disconnecting a source. Saves return success after the transaction commits. A storage error keeps the form open with its entered values and leaves the last saved timetable intact.

Imported IDs currently use `UID@startEpochMillis` in `data/TimetableImporter.kt:201`. An override must retain the original imported ID even when the user changes the displayed start time. It must also be scoped to the feed generation, so an override from a removed subscription cannot accidentally apply to another feed.

Use these coexistence rules:

| Action | Required result |
| --- | --- |
| Refresh the same feed | Replace imported baseline only; retain manual rows and local adjustments. Apply feed updates to fields without local overrides. |
| Edit an imported occurrence | Save a local patch against its original ID. |
| Delete an imported occurrence | Save a tombstone/hidden flag so the next refresh does not restore it. |
| Restore an imported occurrence | Remove its adjustment and show the current feed version. |
| Delete a manual occurrence | Remove that local row. |
| Disconnect/replace a subscription | Remove that feed's baseline and adjustments; keep manually created classes. A successful replacement gets a new feed generation. A failed replacement keeps the working old feed. |
| Feed removes/reschedules a source occurrence | Do not resurrect it through a local patch or guess a match by title/course. Keep unmatched adjustments inactive for review/reset; surface that the source changed. |
| Refresh completes during local editing | Commit imported rows separately from local rows; an in-flight response cannot overwrite a local edit or undo a deletion. |

An upstream reschedule changes the current parser's occurrence ID. Exact matching therefore cannot automatically carry that occurrence's correction forward. Document this first-release limitation and show unmatched corrections for review. Stronger matching belongs with a parser-preserved UID/recurrence identity model.

Serialize source replacement/disconnection and check request generations at commit, retaining the protection already present in `TimetableSession`. Use an expected row revision when saving an existing form so an occurrence that changed or disappeared during editing can be reloaded instead of silently overwritten.

Migration should run lazily for the signed-in owner: read the existing saved URL/snapshot, write baseline/group metadata transactionally and mark migration complete only after success. Preserve URL-only legacy accounts, valid empty imports, timestamps and offline readability. Make migration idempotent and retain the legacy data if it fails. Start the dedicated database with exported schema and intentional future migrations; do not enable destructive fallback for timetable data.

**5. Third implementation slice: one timetable source for foreground and background**

The application layer should observe the account-scoped effective repository and feed its sessions to `TravelEngine`. Home, Schedule and course reminder settings should consume that same source. Remove the direct legacy-store restore from `AppRepositories` and the competing UI-only source of engine updates. Network import ownership may remain a separate component, but it writes into this repository.

Separate subscription status from timetable availability. Suggested state describes subscription URL/refresh/error/last-sync independently from manual entry count and whether timetable setup has been completed. A valid connected empty feed and an established manual timetable with no upcoming classes both need a normal empty-day view; neither should force URL setup. A user with manual classes can still connect a feed later.

Required UI integration:

- Home stops automatically forcing the URL dialog for manual-only use and offers both setup paths for a new account.
- Schedule always offers Add class, and its list/week/reminder controls work with manual sessions.
- Subscription refresh indicators describe imported data; local entries have truthful saved locally status. Local edits do not change the subscription's last successful sync timestamp.
- Profile's removal action explicitly disconnects the subscription. Its existing wording promises broad removal that does not describe additive remote group membership accurately. Update it to describe feed removal and retention of manual classes.
- Preserve reminder preferences for surviving manual classes when disconnecting. The current removal callback clears every course reminder preference.
- Sign-out/account switching stops the old observer, clears current travel/navigation/notifications and guards pending work. Saved timetable rows remain isolated by owner and are restored when that owner returns.

Retain finished occurrences for the engine's existing 15-minute post-class window, including after restart or a refresh within that window. The current cache display helper removes ended classes. Use distinct presentation filtering for upcoming Schedule rows; the engine/Home matching input must still contain the relevant finished occurrence. A refresh must not remove a just-finished class that was already known locally before the window ends. Expiration must use the same engine boundary, rather than introducing a second independent window.

Primary existing files: `data/AppRepositories.kt`, `data/TimetableSession.kt`, `ui/CampusCompanionApp.kt`, `ui/model/CampusModels.kt`, `ui/screens/HomeScreen.kt`, `ScheduleScreen.kt`, `ProfileScreen.kt`, and `ui/components/TimetableSyncStatus.kt`.

**6. Fourth implementation slice: the Schedule editor**

Use one full-screen Compose form for add and edit. It gives the fields room at large font sizes and leaves space for validation and the keyboard. Schedule gets an Add class action and each class card gets an Edit action while retaining its existing navigation action. Both list and week views use the same behavior.

Fields: course code, course title, activity (lecture/tutorial/workshop/other), start date/time, end date/time, building/location and room. Separate end date preserves existing overnight imports. Use `Australia/Melbourne` for entered campus class times, store instants and the academic zone, and convert deliberately for display. Handle nonexistent/ambiguous local times explicitly instead of silently shifting them.

Prefer the existing campus building catalogue for the building picker. Save a canonical building location/code and a room-only value atomically. An imported full venue code such as `PAR-160-B117` must be split or rewritten when the building changes. Editing the building must not leave an authoritative old building embedded in the room field. Unknown/online locations remain saveable with travel-unavailable guidance and unavailable physical navigation; do not fabricate coordinates.

Validation and form behavior:

- Trim text and normalize course/activity consistently with group and reminder matching; require course code/title and an end after the start.
- Show overlaps as an advisory; real timetable clashes must remain representable.
- Identify already ended classes clearly instead of presenting them as upcoming after save.
- Keep the stable occurrence ID through edits, including time edits. Manual creation assigns a UUID once.
- Keep unsaved input across rotation; Cancel does not persist; leave the form only after a successful save.
- Disable duplicate saves while saving and show a recoverable persistence error if necessary.
- Confirm deletion, distinguish deleting a manual class from hiding an imported occurrence, and offer Restore imported version/Restore hidden class for imported data.
- Mark rows as Manual or Edited locally and explain that imported corrections survive refresh.

Add previews for a new manual class, editing an imported class, unresolved building, validation error, save error, dark theme and large text. Check TalkBack labels, accessible targets, scrolling and keyboard behavior.

Suggested new files: `ui/screens/TimetableEditorScreen.kt`, `ui/model/TimetableEditorState.kt`, a pure editor validator and an imported-adjustment review/restore surface. Wire entry points through the existing navigation shell, passing stable IDs rather than an authoritative copied session object.

**7. Preserve collaboration and reminder semantics**

Keep import-derived group specs separate from effective local sessions. `TimetableGroupSpec.key` includes tutorial/workshop weekday, times and location/room; rebuilding server sync input from personal edits could create or join a different remote group. For this first release, only successful import-derived specs drive the existing automatic group sync. Manual additions/corrections/deletions do not automatically create, join or leave groups.

Home should continue matching already joined groups using its finished-course logic. A corrected exact tutorial slot may lose its old exact match, while canonical course and course-tagged fallbacks remain available. Explicit folding preferences, the two-row limit and next-class guidance priority remain covered by the finished Home tests. Timetable edits must not invent unread counts/messages/files or change remote conversation history.

Venue/time edits retain the course/activity reminder setting. A changed course code/activity uses the corresponding setting or its normal default. Do not quietly carry another course's preference across a subject change.

**8. Validation, reviewable commits and completion criteria**

| Area | Meaningful checks |
| --- | --- |
| Engine regression | Same ID A→B, unknown→known, known→unknown/online, same-building room change, start/end changes, movement/arrival reset, unchanged ETA enrichment. Assert requested coordinates and deadline arithmetic, not just display text. |
| Async correctness | Hold A's ETA/weather result; save B or delete A; release the result. A must never be republished, remembered by the engine or notified. Repeat for account/source changes. |
| Consumer correctness | Home, ETA enrichment and reminder preview reject same-ID stale venue/time details. |
| Persistence | CRUD/reload, field merge, tombstones across refresh, restore, legacy migration twice, valid empty feed, storage errors, account isolation and feed replacement/disconnection. |
| Notifications/navigation | Old visible reminder removed/updated; no duplicate venue-only alerts; new-time policy; selected old journey stopped; new Maps action targets the edited building. |
| Integration | Manual-only Home/Schedule/settings, offline process restart, background restoration, concurrent refresh/save, subscription removal with manual classes surviving. |
| Finished Home feature | Finished occurrence remains valid for 15 minutes after restart/refresh; deleted/edited stale occurrence cannot surface; existing group matching/folding/fallback behavior stays intact. |
| UX | Add/edit/cancel/delete/restore, invalid duration, overnight class, unknown building, conflicts, rotation, keyboard, dark mode, font scaling and accessibility. |

Suggested commit order:

1. `fix(context): refresh and invalidate changed class destinations` — producer, pure memory, shared guards and deferred-result tests.
2. `fix(reminders): reconcile edited and deleted class guidance` — delivery validity, visible notifications and selected navigation.
3. `feat(timetable): persist imported and manual occurrences in Room` — repository, merge, migration, restore and concurrency tests.
4. `feat(timetable): share effective sessions with Home and background travel` — application wiring, manual-only state, disconnect/account handling and post-class retention.
5. `feat(schedule): add persistent class editor` — form, validation, add/edit/delete/restore, authoritative building save and previews.
6. `test(timetable): verify manual editing and document P0 coverage` — integration acceptance, targeted instrumentation, README and report/demo evidence.

Use JVM tests for merge/validation/engine/race logic and Room instrumentation for actual database behavior. The existing CI command is `./gradlew --no-daemon testDebugUnitTest assembleDebug`; run it after implementation, plus focused instrumentation on an emulator. Add Compose test dependencies only if implementing automated Compose interaction tests; they are not currently present in the build. Complete device checks for Maps hand-off and notifications because pure models cannot establish those behaviors.

Acceptance walkthrough: start with an account without a URL; manually add COMP90018 at 15:00 in PAR-160; verify Home and the saved reminder setting; edit to another known building without changing the class ID; verify the next completed snapshot uses that building, its ETA/weather and recalculated deadline; tap Maps to check the destination; relaunch offline and verify persistence; connect/refresh an imported timetable and verify local corrections/deletions survive; delete the next class and verify selection/notifications; finish a class and verify existing course-group updates; disconnect the feed and verify manual classes remain; switch accounts and verify isolation.

Record the database design, user flow, passing checks and demo evidence against project-plan P0 and Module A. The Context Engine & Product lead owns repository/editor integration and report traceability; coordinate the accessibility/copy review with the UI/UX lead and consult the Backend lead if group-sync policy is expanded later. The initial release uses the existing collaboration contract.
