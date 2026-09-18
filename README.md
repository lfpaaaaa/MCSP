# MCSP Campus Companion

Campus Companion is a native Android app scaffold for COMP90018. It includes the Compose interface, Supabase authentication, bottom navigation, a Material 3 theme, mock campus data, and first-pass screens for Home, Schedule, Groups, and Profile.

## Authentication

The app supports three sign-in methods:

- Google account
- Apple account through browser OAuth
- Email with a 6-digit one-time code

Authentication uses Supabase Auth. Account sessions are restored automatically. Google and Apple profile names are used when available; otherwise, the app asks for a display name and stores it in Supabase user metadata. The Profile screen displays the signed-in account and provides sign-out.

### Local configuration

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
