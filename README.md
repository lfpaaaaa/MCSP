# MCSP Campus Companion

Campus Companion is a native Android app scaffold for COMP90018. It includes the Compose interface, Supabase authentication, bottom navigation, a Material 3 theme, mock campus data, and first-pass screens for Home, Schedule, Groups, and Profile.

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

Never commit `.env`, `local.properties`, OAuth secrets, or a Supabase service-role key.

## Front-end structure

- `app/src/main/java/au/edu/unimelb/campuscompanion/MainActivity.kt` starts the Compose app.
- `auth/` owns Supabase setup, session state, OAuth, email OTP, and profile metadata.
- `ui/CampusCompanionApp.kt` switches between authentication and the signed-in navigation shell.
- `ui/screens/` contains the first front-end pages.
- `ui/components/` contains reusable UI building blocks.
- `ui/model/` contains mock models and demo data until backend, timetable, and sensing layers are connected.
- `ui/theme/` contains the Material 3 color and typography setup.

## Main screens

- Home: context-aware next-class card, travel status, upcoming classes, and group updates.
- Schedule: timetable list with sync/manual edit entry points.
- Timetable connection: prompts on Home after sign-in, then saves and validates a MyTimetable calendar subscription URL on device; network fetching and ICS parsing are the next data-layer step.
- Groups: course groups, QR join action, chat/file status.
- Profile: authenticated account summary, sign-out, permissions, privacy, and notification preferences.

## Next implementation steps

1. Connect Home to a real context engine state model.
2. Fetch the saved timetable URL, parse ICS events, and cache them with Room.
3. Wire Groups to Supabase repositories.
4. Add CameraX QR scanner screen and navigation route.
5. Add previews and UI tests once the first visual direction is stable.
