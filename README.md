# MCSP Campus Companion

Campus Companion is a context-aware Android app built for COMP90018 (University of Melbourne, 2026 Semester 2). It reads the student's timetable, follows the trip to the next class with the phone's location and motion sensors and routed travel times, and gives each class a group with real-time chat, shared files, six-character join codes and QR invitations. The app is Kotlin and Jetpack Compose; the backend is Supabase (Postgres with row-level security, Realtime, Storage and Edge Functions).

Two commands check a fresh clone:

```bash
./gradlew testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin lintDebug   # JVM unit tests, the debug APK, instrumented-test compilation and Android Lint
npm install && npm run backend:test         # database migrations and pgTAP tests (needs Docker)
```

## Prerequisites

- Android Studio with JDK 17 and an Android emulator
- Node.js 20 or newer
- Docker Desktop for the local Supabase backend

## Authentication

The app supports three sign-in methods:

- Google account
- Apple account through browser OAuth
- Email with a 6-digit one-time code

Authentication uses Supabase Auth. Account sessions are restored automatically. Google and Apple profile names are used when available; otherwise, the app asks for a display name and stores it in Supabase user metadata. The Profile screen displays the signed-in account and provides sign-out.

The backend migration creates `public.profiles`, automatically mirrors new Auth users into it, and applies row level security so users can only read or update their own profile.

## Start the backend locally

Start Docker Desktop, then run these commands from the project root:

```bash
npm install
npm run backend:start
npm run backend:status
```

The status command prints the local API URL and publishable/anonymous key. Put them in the untracked `local.properties` file so an Android emulator can reach the backend:

```properties
SUPABASE_URL=http://10.0.2.2:54321
SUPABASE_PUBLISHABLE_KEY=the-key-printed-by-backend-status
```

`10.0.2.2` is the Android emulator's route to the Mac's localhost. The debug build permits this local HTTP connection; release builds still require HTTPS.

Open [http://127.0.0.1:54324](http://127.0.0.1:54324) to view local login emails and their six-digit codes. Local messages are captured here instead of being sent to a real inbox.

Useful backend commands:

```bash
npm run backend:reset   # rebuild the local database from migrations
npm run backend:test    # run database and RLS tests
npm run backend:stop
```

Google and Apple providers are disabled locally until credentials are available. Copy `.env.example` to `.env`, add the provider credentials, then set the matching provider's `enabled` value to `true` in `supabase/config.toml`.

## Start the Android front end

1. Open the project directory in Android Studio.
2. Wait for Gradle sync to finish and select JDK 17 if Android Studio asks for a Gradle JDK.
3. Start a Pixel emulator.
4. Select the `app` run configuration and click Run.

### Cloud Supabase configuration

Create a Supabase project, then add these values to the untracked `local.properties` file in the project root:

```properties
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_PUBLISHABLE_KEY=your-publishable-key
```

`local.properties.example` contains the same placeholders. The app remains buildable without credentials and shows the disabled login interface with a configuration message.

In the Supabase dashboard:

1. Add `campuscompanion://login-callback` to Authentication > URL Configuration > Redirect URLs.
2. Enable and configure the Google provider.
3. Enable and configure the Apple provider. Apple OAuth secrets need periodic renewal according to Apple's requirements.
4. In the email template used for passwordless sign-in, send `{{ .Token }}` so the app receives a 6-digit code instead of a magic link.
5. Configure custom SMTP before production email testing; Supabase's default sender is intended for limited project testing.

To apply this repository's database migration to a linked cloud project:

```bash
npx supabase login
npx supabase link --project-ref your-project-ref
npx supabase db push
```

### Group codes and invitations

Every group gets a six-character join code when it is created (`groups.join_code`, letters and digits that are hard to confuse). The code is shown to the person who starts the group and works for five minutes, long enough to read it out in class; after that the group is joined through QR invitations, which carry short-lived invite tokens. `join_group_with_token` accepts codes and invite tokens, and a wrong code costs a one-second wait on the server, which keeps guessing impractical. Groups created from the timetable (`sync_timetable_groups`, see `docs/timetable-groups.md`) are joined automatically by everyone who imports the same class.

NFC join/invite buttons are temporarily hidden on the Groups page, in group settings and in
the join-code fallback, following inconsistent behavior across phones. QR sharing/scanning and
six-character join codes remain available; the NFC implementation is retained for later fixes.

The QR code (group settings > QR code) encodes the invite link `campuscompanion://join?token=…`, so it works both with the app's own scanner (Groups > Scan QR, built on ZXing) and with the phone's camera app, which opens the link in Campus Companion. The scanner also accepts a QR code that simply contains a six-character join code, and the image can be saved or shared from the dialog.

### Group nicknames

Group settings > Your name in this group saves a nickname for the signed-in membership (1–40 characters). Each group can have a different nickname; the account profile is unchanged. Member lists, message history, file authors and new message push previews use the group nickname, falling back to the account name when none has been set. Open chats and member lists refresh on membership updates, with a periodic retry for missed events. A failed save keeps the settings page open and displays an error.

Apply migration `20261010120000_group_nicknames.sql` before installing this version, and redeploy `notify-message` when using a hosted backend so notification previews also use group nicknames.

### Travel times

The `route-eta` Edge Function returns travel times for departure reminders. Walking and driving times come from the [FOSSGIS OSRM servers](https://routing.openstreetmap.de/about.html) and public transport times from [Transitous](https://transitous.org/api/); neither needs an API key. Both services are run by volunteers, so the function rounds positions to about 110 m, caches answers, starts calls to each service at least one second apart, and applies the fair-use limits in `private.route_limits`. When a limit is reached or a service is unavailable, the app falls back to a straight-line estimate, which the next-class card marks as approximate ("~12 min", "Walking (approx.)").

On the device, `context/TravelEngine` joins the timetable, the location and motion sensors and these travel times into the full context state and buffered departure deadline; `context/DepartureReminders` turns the "leave soon" and "running late" states into one notification each per class, which the activity switches on the Schedule screen's "Departure reminders" page control. Its "Arrival buffer" slider adds extra time to arrive before class, separately from weather and classroom-entry allowances. The leave-soon warning remains fixed at five minutes before the engine's calculated departure deadline. The settings preview shows that same live deadline and warning time when a travel estimate is available for the current or next class. Each course's Lecture, Tutorial and Workshop have independent settings, shared across that activity's recurring classes. Settings are saved on the device per account; the notifier reads the matching activity's saved setting directly, including in the background. Turning an activity off removes its visible departure notifications without muting other activities or group messages. Existing reminder choices are retained until the activity is edited. The building of each class is looked up from the location code in the timetable (for example `PAR-160`) in `assets/uom_building_outlines.geojson`. Rain or heat at the class's building adds a few minutes to the departure buffer; the weather comes from [Open-Meteo](https://open-meteo.com), which needs no key, is free for non-commercial use and licenses its data CC BY 4.0, so screens that show it must say "Weather data by Open-Meteo.com".

Home receives `TravelSnapshot` directly. One combined card shows class details, the engine's current instruction, its departure deadline/countdown, the travel estimate and the individual arrival, weather and room-entry buffers, alongside Google Maps navigation. Instructions change from "Leave in 5 minutes" or "Leave now" to "On your way", "Class starts in …" and "In class until …". Travel estimates stay labelled when approximate, with the appropriate routing and weather credits. Departure prompts stop once the student is travelling or has arrived; long breaks and days without more classes have their own guidance. An updating state prevents a previous class's snapshot from supplying a new class's guidance.

Changing a class's venue or times with the same occurrence ID now clears outdated guidance immediately. The engine resolves the current building on each refresh, resets arrival and movement history when the tracked class or its resolved building changes, and rejects ETA/weather results started before a timetable change or account reset. Room, title and time corrections within the same building keep the student's arrival: arrival is entered within 75 m and kept until 200 m because indoor GPS is often that far off, so resetting it would turn a student who is already there into "Running late" or "Time to leave", and the reminder history (keyed by class ID and start) would let that alert through. Home, timetable ETA enrichment and reminder timing previews share an occurrence comparison that includes course/activity, times and venue while excluding display titles and transient estimates. Global GPS/movement inputs remain available for the new trip. Known-to-unknown venue changes clear travel guidance; unknown-to-known changes begin resolving normally. Manual timetable editing must save the building and room consistently because imported room text can include a full building code.

Departure reminders stay synchronized with the current next class. At most one is visible; it is silently updated when class details change and removed when the class is no longer relevant, reminders are disabled, the student sets off or arrives, or the account changes. Dismissed prompts are not restored, and each warning is delivered at most once per class occurrence and account. While the timetable or travel context is loading, the current prompt remains until a fresh result is ready.

Directions selected in the app follow timetable changes. Room, title and activity corrections update the labels while keeping the routes and current trip. Moving the class to another building ends tracking and asks for new routes; removing the class or its campus building closes directions with an explanation. Account changes clear the selected journey, and Start is disabled while routes reload. Finished classes close their directions when the timetable refresh removes them from the upcoming list. An external Google Maps session already open cannot be retargeted.

For the engine's 15-minute post-class window, Home uses the finished occurrence validated by `HomeCard` to put that course's group below the class guidance, labelled **After COMP90018** (for example). It shows the existing unread count, latest message preview and latest shared filename; these summaries do not imply the activity happened after class. Selection prefers the canonical `COMP90018|course` group, then the exact tutorial/workshop `timetableKey`, then a joined or user-created group tagged with the course. Other tutorial slots never match by course code alone. The next class's guidance remains first, including when departure is urgent.

A matching repository membership can appear on Home even if it folded automatically after its final class; explicit user folds are honoured and the Groups screen keeps its normal folding rules. Home shows at most two distinct groups, retaining ordinary order when there is no validated finished occurrence or no match. Empty matching groups still open normally and show the repository's empty-message summary. Removing or editing the finished occurrence clears its context, and expiry comes from the engine rather than a second Home timer.

The ICS parser and account-scoped timetable cache retain finished occurrences for this same 15-minute window, including refreshes and session reconstruction after recreation or offline restart. Retained history does not consume the 500 upcoming-class import slots or create new tutorial/workshop memberships. Schedule marks these rows finished; the engine removes the post-class selection at the exclusive expiry boundary on reevaluation.

Deploy the function after pushing the migrations:

```bash
npx supabase functions deploy route-eta
```

Transitous serves open-source, non-commercial projects and asks projects to contact its team before using its routing API. Public transport routing therefore stays off until it is switched on:

```bash
npx supabase secrets set TRANSITOUS_ENABLED=true
```

### Push notifications

New group messages are pushed to the other members' devices through Firebase Cloud Messaging. Devices register their token with `register_device_token`; a trigger on `messages` asks the `notify-message` Edge Function (through `pg_net`) to send one data message per device, and the app shows the notification. The message body is only sent as a short preview. Without the configuration below the app builds and runs with push notifications switched off.

1. In the Firebase console, add an Android app with the package name `au.edu.unimelb.campuscompanion` and put its `google-services.json` in `app/` (kept out of git; share it privately and include it in the submitted code).
2. Firebase console > Project settings > Service accounts > Generate new private key, then, from the folder holding the downloaded JSON:

   ```bash
   npx supabase secrets set FCM_SERVICE_ACCOUNT="$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))))' service-account.json)"
   ```

3. Create a shared key for the database-to-function call and store it in both places:

   ```bash
   openssl rand -hex 32                       # the key
   npx supabase secrets set NOTIFY_MESSAGE_KEY=the-key
   ```

   and in the SQL editor of the dashboard:

   ```sql
   select vault.create_secret('https://your-project.supabase.co', 'project_url');
   select vault.create_secret('the-key', 'notify_message_key');
   ```

4. Deploy: `npx supabase db push` and `npx supabase functions deploy notify-message`.

Screens that show routed times must credit the data: "© OpenStreetMap contributors" linked to <https://www.openstreetmap.org/copyright>, a "Fix the map" link to <https://www.openstreetmap.org/fixthemap>, and, for public transport, a link to <https://transitous.org/sources/>. The next-class card carries the OpenStreetMap credit under its travel times, and the About section of the Profile screen lists every data source with its links; `ui/about/DataSources.kt` is the one place the wording lives.

Never commit `.env`, `local.properties`, OAuth secrets, or a Supabase service-role key.

## Continuous integration

Two GitHub Actions workflows in `.github/workflows/` run on every pull request and on pushes to `main`:

- `android.yml` runs the JVM unit tests, assembles the debug APK, compiles the instrumented tests and runs Android Lint (a Lint error fails the build) on a clean runner, without `local.properties` or `google-services.json` (the app then builds with the in-memory fakes and push notifications off). The test report, the Lint report and the APK are attached to the run.
- `backend.yml` starts the local Supabase database, applies every migration from scratch and runs the pgTAP tests in `supabase/tests/database/`; a second job type-checks the Edge Functions with `deno check`. It runs when anything under `supabase/` changes.

Both are the same commands as in the quick check above, so a green run means a fresh clone builds and the backend schema is consistent.

Every pull request starts from `.github/PULL_REQUEST_TEMPLATE.md` (what and why, how it was tested, the rubric criteria it touches, a short checklist) and needs one approving review before it is merged. Code style follows `.editorconfig`; there is no automated formatter, so reviewers check names, constants and KDoc by hand.

Debug builds turn on `StrictMode` in `CampusCompanionApplication`: disk and network access on the main thread, slow calls and leaked resources are logged under the `StrictMode` tag (`adb logcat -s StrictMode`), never fatal. Release builds are unaffected.

## Security notes

- Every table has row-level security; members only ever read their own groups. Writes that must cross groups (joining with a code or token, counting members, marking messages as read) go through `SECURITY DEFINER` functions in the `private` schema, exposed through thin `SECURITY INVOKER` wrappers in `public`. The pgTAP tests exercise the policies as different users.
- The app only ever holds the publishable key; the service-role key stays in the dashboard and in Edge Function secrets. Invite tokens expire after ten minutes and have a use limit; a wrong join code costs a one-second server-side wait.
- Positions are coarsened before they leave the device (about 110 m for routing, about 1 km for weather). The server keeps travel times in a cache keyed by the rounded points and a per-user request count per day, never a user's positions; the sensor readings themselves stay on the phone, and only the derived travel state is shown.
- `allowBackup` is off, so the on-device message cache and the sign-in session are not copied into cloud backups or device transfers; everything is re-fetched after sign-in.
- Permissions are limited to what the features use: location (travel context and geofencing), activity recognition (motion state), camera (photos for the chat and QR codes), NFC (invitations), notifications and the foreground-service permissions for the sensing service. The app does not record audio. The Profile screen shows the real state of each permission (`ui/profile/AppPermissions.kt`); tapping a row asks for the permission or opens the app's system settings when the system no longer shows the dialog.
- Nothing secret is committed: `.env`, `local.properties`, `google-services.json` and OAuth or service-role keys are ignored by git.

## Front-end structure

- `app/src/main/java/au/edu/unimelb/campuscompanion/MainActivity.kt` starts the Compose app.
- `auth/` owns Supabase setup, session state, OAuth, email OTP, and profile metadata.
- `ui/CampusCompanionApp.kt` switches between authentication and the signed-in navigation shell.
- `ui/screens/` contains the first front-end pages.
- `ui/chat/` holds the state of an open group chat (`GroupChatSession`: the timeline of messages and shared files, uploads, connection state and latency samples) that `GroupChatScreen` renders; `ChatLatency` log lines record send round trips and delivery times for the responsiveness measurements.
- `ui/components/` contains reusable UI building blocks.
- `ui/model/` contains the models the screens render; `data/fake/` holds the in-memory repositories and sample data used when no backend is configured.
- `data/` holds the repositories (groups, invites, chat with its Room cache, files, routed travel times, weather, push tokens) and the Supabase data sources behind them; `context/` the travel engine; `sensing/` the sensor pipeline and foreground service; `push/` Firebase Cloud Messaging.
- `ui/theme/` contains the Material 3 color and typography setup.

## Main screens

- Home: context-aware next-class card (with Google Maps navigation and a **Direction to building** compass), travel status, upcoming classes, and group updates.
- Schedule: switch between a date-grouped list (10 classes per page, with all imported upcoming classes accessible through paging) and a Monday–Sunday calendar selector with all classes for the selected day. Date headings appear once per day, times use a 24-hour clock, and each class retains Google Maps navigation and the **Direction to building** compass. Sync and departure reminder settings remain available.
- Attachments: each staged photo, video, camera capture or document has an **Allow others to download** switch (on by default). Camera confirmation stages the photo so the sender can choose before tapping Send. Consent is saved with the upload queue and in `shared_files.allow_downloads`; older attachments default to enabled. Members can preview images, videos, PDFs and text in the app; only permitted recipients and the uploader can download or open the original in another app. Unsupported formats show a preview-unavailable message. There is no private-attachment/device-unlock feature.
- Editing classes by hand: Schedule has **Add class**, and the pencil on each class opens the same editor (course code, name, activity, building, room, date and times). A class added by hand is stored in Room under the account with a `manual:` identity, so it survives timetable refreshes and removing the URL, and reminders, travel times and directions treat it like an imported one. Editing an imported class stores a correction that keeps the class's identity (the original start is remembered so a refresh can still match it); **Restore imported details** drops it. **Hide class** removes an imported class from every screen until **Show hidden classes** brings it back; **Delete class** removes a class added by hand. A failed save is reported in the editor and leaves the timetable as it was. A timetable that consists only of classes added by hand works without a URL.
- Timetable connection: imports a MyTimetable subscription and saves the URL, parsed sessions, class-group information and last successful update time per account. Home and Schedule show saved classes immediately on restart while refreshing in the background. Failed refreshes keep the cache; Refresh retries without re-entering the URL. Removing the subscription deletes its cache.
- Groups: course groups, join by code or QR code, and the group chat: a group you create or join opens straight away (a new group shows its 6-character join code on top of the chat, and the code stays under Group settings → View join code for five minutes); messages and shared files come from Supabase (realtime feed plus the Room cache, so saved messages stay readable offline), with optimistic sending, retry of failed messages, paging of older history, upload progress and photo previews.
- Profile: account summary, travel preferences (walking range and the mode for longer trips), the timetable URL, the real state of each permission with a note on where location data goes, credits for the data sources, the app version, and sign-out.

## Status and next steps

Working end to end: sign-in (Google and email code), timetable import from a MyTimetable subscription URL (with a saved copy for offline use), the next-class card with travel estimates, routed travel times with weather buffers, departure reminders ("time to leave" and "running late" notifications, switched on and timed per course from the Schedule screen), groups with join codes and QR invitations, real-time chat with an offline cache that resends what failed offline, shared files and photos, push notifications for new messages, and a Profile screen with the real permission state and the data credits.

In a 50-message, two-emulator test against the Sydney Supabase project, send confirmation had a 58 ms median and 77 ms p95; peer delivery took about 0.1–0.6 seconds, with no lost messages.

Still to do, in order:

1. Restore the open screen when the system has killed the app in the background (it currently comes back on Home unless a chat was open).
2. Strip sensor debug logging from release builds and ask Transitous for permission before switching public transport routing on.
3. Confirm the source and licence of the bundled campus building outlines and name them in the About section.
4. Apple sign-in once an Apple developer account is available.

## Licence

The code is released under the MIT Licence (see `LICENSE`). The app shows data from [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors routed by the FOSSGIS OSRM servers, from [Transitous](https://transitous.org/sources/) when public transport routing is enabled, and weather by [Open-Meteo](https://open-meteo.com) (CC BY 4.0); screens that show these must carry the credits described above.

## Class navigation with Google Maps

Home and Schedule expose **Navigate with Google Maps**. A tap resolves the session's teaching
building using the bundled UoM coordinates and opens the external Google Maps app directly, with
that coordinate as the destination. If Maps is absent/disabled, the same HTTPS link opens in a
browser. The link uses the estimated travel mode when available, otherwise the saved longer-distance
mode. It omits the origin so Google Maps can use its own current location. Google Maps decides whether
to show a route preview or start supported navigation; the user can change travel mode there.

Unmatched/online classes show an error rather than opening an unrelated building. Directions end at
the teaching building; the timetable still supplies the classroom/floor. Returning from Maps leaves
Home or Schedule available, without an intermediate in-app map page.

The external Maps URL needs **no Google API key, Maps SDK or billing configuration in this app**.
The Android map dependency and key manifest entry have been removed. Voice guidance, street/building
labels, traffic and rerouting are provided by the external Google Maps app.
Official URL behavior: <https://developers.google.com/maps/documentation/urls/get-started>.

Google Maps does not send its selected route or stops back to Campus Companion. Opening it does not
start the app's own get-off reminders. The existing separate route/trip tracking code is retained,
including its notification controls, but the Home/Schedule navigation action no longer enters that
route-selection workflow. It must not infer that the Google Maps route matches an earlier trip.
Changing the destination cancels any earlier separately selected reminder journey.

### Optional server travel estimates

Home travel time and departure reminders use the existing router/offline estimates, separate from
the external navigation link. The retained Google route/trip code can use the optional server setup:
enable **Routes API** in a Google Cloud project with billing and put a
server key restricted to Routes API in the Supabase Edge Function secret `GOOGLE_ROUTES_API_KEY`.
For local development, use a git-ignored `.env` with `supabase functions serve --env-file .env`.
Never put the server key in the Android APK. The `google-routes` function authenticates users and
uses `private.consume_route_quota`; hosted deployments need the existing route quota migration.
This setup does not switch the context engine's ETA provider to Google.

### Live Home travel time and departure notifications

Home shows **Travel time** (duration and mode), without a distance metric. The location foreground
service requests fixes every 10 seconds during active class/navigation sensing and every 60 seconds
in battery-saving NORMAL mode; the engine also reevaluates the clock every 30 seconds. Fresh,
target-scoped GPS, motion/activity and nullable fusion evidence arrive together. New physical
decisions require usable measurements; a temporary signal outage can preserve an established trip
and last-known guidance. Explicit stop, permission loss or account replacement clears sensing
evidence and guidance. Walking, running and vehicle activity require progress toward the classroom.
Routing requests are limited to once per destination every three minutes. Walking/driving estimates
are cached for one day, transit estimates for ten minutes, and a recent routed estimate can be reused
for ten minutes while the user moves. Otherwise the offline straight-line estimate is used.

The default ten-minute setting is an **arrival buffer**. Departure deadline = class start − travel
minutes − arrival buffer − classroom entry allowance − weather allowance. For example, with entry
and weather allowances set to zero, a class at 11:00 with a 20-minute journey and ten-minute arrival
buffer has a 10:30 departure deadline; Leave soon starts at 10:25. Notifications follow the context
engine's Leave soon or Running late state and are suppressed in its other states, including En route,
Arrived and In class. Lecture/tutorial/workshop switches and individual buffers still apply.
Notifications are generated on the phone, so FCM/server push credentials are not
required. Android notifications and location permission must be enabled; the foreground service must
be running. Force-stop prevents background delivery until the app is opened again.

The application restores the signed-in user's cached timetable for reminders and no longer clears
it when a screen is destroyed. Successful reminder deliveries are saved on the device by account,
class ID, class start time and context state, so restarting the app does not repeat them. A delivered
Running late alert also prevents a later Leave soon warning from replacing it. Failed delivery
attempts remain eligible on the next travel snapshot or reminder preference update, while the
context still calls for a reminder. Saved records expire when the class ends and are pruned on the
next successful delivery. The notification links to Schedule.

Step 6 adds journey and emulator acceptance tests, plus a disposable ICS fixture generator at
`scripts/create-context-rehearsal-ics.py`. Debug `DeparturePrompt` logs identify
notification post/update/withdraw API submissions without logging account IDs or reminder text.
Physical recognition, actual geofence delivery, battery measurement and live-backend rehearsal
remain separate acceptance work. The fixture Home recreation host exists only in debug builds.

## Building compass

Home's next-class card and each Schedule class have a **Direction to building** action. The compass
page resolves the class building from the bundled campus locations and combines a fresh, precise
location with the accelerometer/magnetometer heading. It corrects magnetic declination and display
rotation, animates the shortest turn across north, and replaces the arrow with a nearby-building
message within 75 m. No map API key or route request is needed for the compass. The arrow shows the
straight-line direction; the **Navigate with Google Maps** button on the compass page uses the current
location and the Profile walking threshold to choose walking nearby, or the selected longer-distance
mode (driving/public transport) beyond it, and falls back to the Profile mode without a usable
distance. The handoff explicitly selects that mode in Google Maps, including the browser fallback.

Only the visible compass page requests its location and heading updates; leaving it or locking the
screen stops both without changing the app's independent trip/reminder service. Low magnetic
accuracy, a tilted phone, missing sensors and stale, switched-off or denied location each have an
explicit message, and every arrow state also has a text direction for TalkBack. Unit tests cover
bearings, declination, wraparound, sensor/location freshness, display rotation, destination changes
and the travel-mode choice. Physical-device checks should include portrait/landscape rotation,
outdoor pointing, calibration, background/resume, permission denial and returning from Google Maps.

## Editing a profile

**Profile → Edit profile** lets the signed-in user change their display name (2–80 characters),
choose a photo using the system document picker, or remove the photo. The email is shown as the
sign-in account. Cancel discards the draft; saving waits for the backend and retains the draft on
failure. The request lives in the ViewModel across configuration changes. The profile card refreshes
after success, and Auth stores the updated user metadata for subsequent sign-ins.

Names and avatar references are updated together via Supabase Auth `updateUser`; the existing
`sync_auth_user_profile` trigger copies them to `public.profiles`. Group member lists use the new
account name where no group nickname is set. Existing group nicknames remain separate. Member
lists also display avatars, with initials when photos cannot be loaded.

Apply `20261011150000_profile_avatars.sql` before using photo upload on a hosted backend. It creates
a private `profile-avatars` bucket (JPEG only, at most 1 MiB). Picked images are bounded to 20 MiB,
orientation-corrected, center-cropped to 512×512 and re-encoded without EXIF metadata. The app stores
`storage://profile-avatars/<user-id>/<uuid>.jpg` in `avatar_url`, rather than an expiring signed URL.
Only the owner may upload/delete; the owner and current group peers may read the current photo.
Removal stores an explicit empty avatar URL so an OAuth photo does not reappear. Old owned photos
are deleted after confirmed saves; a failed cleanup does not undo the saved profile. An ambiguous
save failure retains the new object because Auth may already have committed its reference.

## Attachment download permissions

Apply `20261011170000_attachment_download_permissions.sql` and deploy `group-file-url` before updating clients:

```sh
supabase db push
supabase functions deploy group-file-url
```

For local development use `supabase migration up --local` and `supabase functions serve`. No extra secret is required beyond the standard Supabase function environment. The endpoint verifies the caller through Auth and calls `group_file_access` with that user's JWT. Download requests check group membership and the uploader's consent; preview requests check membership. Only the server uses the service credential to sign a short-lived preview URL. Storage RLS also blocks recipients from obtaining direct object/download URLs when consent is off. Uploaders can retrieve their own originals while still in the group. Consent is chosen at upload time; recipients cannot edit it.

The UI rechecks server permission before exporting cached bytes or sharing a FileProvider URI. Preview necessarily sends content to a device; this restriction is not DRM and cannot prevent screenshots or extraction by a modified client. Unsupported document formats are not passed to external viewers when downloads are disabled.

## Shared delivery reliability

Before running the updated Android client, apply `20261014090000_shared_delivery_reliability.sql`
and deploy the deletion and cleanup functions to the same backend as the app:

```sh
supabase db push
supabase functions deploy group-file-delete
supabase functions deploy group-file-cleanup
```

For local development, use `supabase migration up --local` and `supabase functions serve`.
These are additive migrations; do not reset a database containing app data. Upload finalization
uses the queue's persisted UUID and immutable object path, checks current membership and the
stored object's size/type, and returns the existing file after a lost response. Queued bytes
are copied into private app storage and retained until confirmation or explicit removal.
Older queued items acquire their stable identity from the existing queue ID.

The uploader can delete from the attachment viewer. `group-file-delete` verifies the caller,
creates a durable cleanup job, removes the object through the Storage API, then removes the
file record. Failure keeps the attachment visible with **Retry deletion**. Open chats reconcile
their file catalog every 30 seconds so other members also see deletions. Group dissolution
and direct metadata deletion also queue cleanup, including unregistered uploads in a dissolved
group. Storage download-consent policies remain in effect. Deleting metadata directly from
`storage.objects` does not remove stored bytes; cleanup must use the Storage API
([Supabase deletion guidance](https://supabase.com/docs/guides/storage/management/delete-objects)).

Schedule a server-side POST to `/functions/v1/group-file-cleanup` every minute with
`Authorization: Bearer <service-role-key>`. Store this credential in the scheduler's secret
store (for example Supabase Vault); never put it in Android configuration or source control.
The worker accepts only that credential, handles at most 50 jobs per invocation, and retries
pending jobs on subsequent invocations. Deployment alone does not set up this schedule.
For a local/manual run, POST to the same endpoint with the local server credential.
Monitor `public.group_file_cleanup_jobs` as an administrator: `state`, `attempts` and
`last_error` expose failures without logging filenames or signed URLs. Keep completed job
records as tombstones so a delayed upload or deletion retry cannot recreate a removed file.

Successful code/QR/NFC joins publish and cache the joined group before the summary refresh.
Chat reconnects advance their recovery cursor only after successful history synchronization;
after process restart they reconcile the retained cache rather than trusting a newer live row.

Validation for this increment is recorded in
[`docs/shared-delivery-verification.md`](docs/shared-delivery-verification.md).

## Group files and join codes

Group settings now lists **Members → Group files → View join code → Share QR code**.
Opening Members or Files and returning keeps pending fold, mute and nickname
edits, including through Activity recreation. Cancel discards the draft; Save applies it.
Timetable groups keep their automatic membership and hide invitation/exit controls.

Group files searches file names/uploaders and filters All, Photos, Videos, Audio and Documents,
newest first. It reuses the protected attachment viewer and download consent. Only the uploader
sees Delete; failed deletion retains Retry. Refresh and automatic reconciliation update the
catalog, while offline copies are marked as saved. Access denial hides saved rows and pauses
automatic requests until a successful explicit refresh. Account changes invalidate the
in-memory catalog and in-flight results. Fresh file catalogs also confirm queued uploads so
deleting a completed file before returning to chat cannot leave a stuck upload indicator.

View join code shows the existing six-character code and its remaining original five-minute
lifetime from server group creation. Reopening/recreating the screen never starts a new
lifetime. Expired/unavailable codes offer a QR alternative, and Copy checks expiry again.

This increment adds no backend migration. Upload/deletion still require the shared delivery
migration/functions/cleanup schedule above. See
[`docs/group-settings-features-verification.md`](docs/group-settings-features-verification.md)
for automated and emulator evidence and remaining active-backend acceptance checks.


## Voice messages

Tap the microphone beside the group chat composer, then **Start**. Microphone permission is
requested on demand and can also be managed in Profile → Permissions. The timer and input
meter use actual recorder readings. Capture uses mono AAC in M4A at 96 kbps, with a ten-minute
limit. **Stop** opens playback/seek review; **Record again** or **Cancel → Discard** removes the
unsent clip. **Use recording** saves a local composer draft. Choose download consent, then
**Send** to upload through the existing durable queue.

Switching apps, locking the screen or leaving the recording screen stops capture and preserves
a valid clip for review. Returning does not restart capture. Stopped and staged drafts survive
recreation/restart and belong to one account/group. Invalid interrupted containers are discarded
with a visible explanation. Queued recordings retain their original bytes until confirmation or
explicit failed-upload removal; a stable UUID avoids duplicate registration on retry. Playback
pauses on background/audio interruption and offers Retry for unavailable audio. Members can
preview permitted audio in chat and Group files, while original downloads keep the existing
uploader-consent checks.

Apply `20261014100000_voice_recording_metadata.sql` to the same backend as the app before using
voice sending, after the shared delivery migration above. It adds duration/voice metadata and
extends the idempotent finalization RPC with defaults for older clients. Use `supabase db push`
for the linked project or `supabase migration up --local` locally. This increment adds no new
Edge Function. Existing preview/deletion functions and cleanup scheduling remain required.
Automatic transcription, transcript controls and worker deployment are deferred by the team;
voice capture and playback have no transcription dependency.

See [`docs/voice-messages-verification.md`](docs/voice-messages-verification.md) for validation
results and remaining physical-phone/two-account acceptance checks.
