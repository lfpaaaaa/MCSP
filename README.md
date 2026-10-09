# MCSP Campus Companion

Campus Companion is a context-aware Android app built for COMP90018 (University of Melbourne, 2026 Semester 2). It reads the student's timetable, follows the trip to the next class with the phone's location and motion sensors and routed travel times, and gives each class a group with real-time chat, shared files, six-character join codes and NFC invitations. The app is Kotlin and Jetpack Compose; the backend is Supabase (Postgres with row-level security, Realtime, Storage and Edge Functions).

Two commands check a fresh clone:

```bash
./gradlew testDebugUnitTest assembleDebug   # JVM unit tests and the debug APK
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

Every group gets a six-character join code when it is created (`groups.join_code`, letters and digits that are hard to confuse). Members see it and pass it on; typing it joins the group, and an owner can replace it with `reset_group_code`. QR codes and NFC tags carry short-lived invite tokens instead; `join_group_with_token` accepts both. A wrong code costs a one-second wait on the server, which keeps guessing impractical.

The QR code (group settings > QR code) encodes the invite link `campuscompanion://join?token=…`, so it works both with the app's own scanner (Groups > Scan QR, built on ZXing) and with the phone's camera app, which opens the link in Campus Companion. The scanner also accepts a QR code that simply contains a six-character join code.

### Travel times

The `route-eta` Edge Function returns travel times for departure reminders. Walking and driving times come from the [FOSSGIS OSRM servers](https://routing.openstreetmap.de/about.html) and public transport times from [Transitous](https://transitous.org/api/); neither needs an API key. Both services are run by volunteers, so the function rounds positions to about 110 m, caches answers, starts calls to each service at least one second apart, and applies the fair-use limits in `private.route_limits`. When a limit is reached or a service is unavailable, the app shows an approximate offline estimate.

On the device, `context/TravelEngine` joins the timetable, the location and motion sensors and these travel times into the travel state (upcoming, leave soon, en route, running late, arrived) that the screens show; `context/DepartureReminders` turns the "leave soon" and "running late" states into one notification each per class, which the Schedule screen's reminder switch and lead-time slider control. The building of each class is looked up from the location code in the timetable (for example `PAR-160`) in `assets/uom_building_outlines.geojson`. Rain or heat at the class's building adds a few minutes to the departure buffer; the weather comes from [Open-Meteo](https://open-meteo.com), which needs no key, is free for non-commercial use and licenses its data CC BY 4.0, so screens that show it must say "Weather data by Open-Meteo.com".

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

Screens that show routed times must credit the data: "© OpenStreetMap contributors" linked to <https://www.openstreetmap.org/copyright>, a "Fix the map" link to <https://www.openstreetmap.org/fixthemap>, and, for public transport, a link to <https://transitous.org/sources/>.

Never commit `.env`, `local.properties`, OAuth secrets, or a Supabase service-role key.

## Continuous integration

Two GitHub Actions workflows in `.github/workflows/` run on every pull request and on pushes to `main`:

- `android.yml` runs the JVM unit tests and assembles the debug APK on a clean runner, without `local.properties` or `google-services.json` (the app then builds with the in-memory fakes and push notifications off). The test report and the APK are attached to the run.
- `backend.yml` starts the local Supabase database, applies every migration from scratch and runs the pgTAP tests in `supabase/tests/database/`; a second job type-checks the Edge Functions with `deno check`. It runs when anything under `supabase/` changes.

Both are the same commands as in the quick check above, so a green run means a fresh clone builds and the backend schema is consistent.

## Security notes

- Every table has row-level security; members only ever read their own groups. Writes that must cross groups (joining with a code or token, counting members, marking messages as read) go through `SECURITY DEFINER` functions in the `private` schema, exposed through thin `SECURITY INVOKER` wrappers in `public`. The pgTAP tests exercise the policies as different users.
- The app only ever holds the publishable key; the service-role key stays in the dashboard and in Edge Function secrets. Invite tokens expire after ten minutes and have a use limit; a wrong join code costs a one-second server-side wait.
- Positions are coarsened before they leave the device (about 110 m for routing, about 1 km for weather). The server keeps travel times in a cache keyed by the rounded points and a per-user request count per day, never a user's positions; the sensor readings themselves stay on the phone, and only the derived travel state is shown.
- `allowBackup` is off, so the on-device message cache and the sign-in session are not copied into cloud backups or device transfers; everything is re-fetched after sign-in.
- Permissions are limited to what the features use: location (travel context and geofencing), activity recognition (motion state), camera (photos for the chat and, later, QR codes), NFC (invitations), notifications and the foreground-service permissions for the sensing service. The app does not record audio.
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

- Home: context-aware next-class card, travel status, upcoming classes, and group updates.
- Schedule: timetable list with sync/manual edit entry points.
- Timetable connection: prompts on Home after sign-in, then saves and validates a MyTimetable calendar subscription URL on device; network fetching and ICS parsing are the next data-layer step.
- Groups: course groups, join by code, QR code or NFC, and the group chat: messages and shared files come from Supabase (realtime feed plus the Room cache, so saved messages stay readable offline), with optimistic sending, retry of failed messages, paging of older history, upload progress and photo previews.
- Profile: authenticated account summary, sign-out, permissions, privacy, and notification preferences.

## Status and next steps

Working end to end: sign-in (Google and email code), timetable import from a MyTimetable subscription URL, the next-class card with travel state, routed travel times with weather buffers, departure reminders ("time to leave" and "running late" notifications, with the lead time and switch saved from the Schedule screen), groups with join codes, QR and NFC invitations, real-time chat with an offline cache, shared files and photos, and push notifications for new messages.

Still to do, in order:

1. An attribution page (OpenStreetMap/OSRM, Transitous, Open-Meteo, building data) and Profile permission rows that reflect the real permission state.
2. Cache the timetable on the device so the Schedule screen opens offline.
3. Strip sensor debug logging from release builds and ask Transitous for permission before switching public transport routing on.
4. Apple sign-in once an Apple developer account is available.

## Licence

The code is released under the MIT Licence (see `LICENSE`). The app shows data from [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors routed by the FOSSGIS OSRM servers, from [Transitous](https://transitous.org/sources/) when public transport routing is enabled, and weather by [Open-Meteo](https://open-meteo.com) (CC BY 4.0); screens that show these must carry the credits described above.
