# MCSP Campus Companion

Campus Companion is a native Android front-end scaffold for COMP90018. The current codebase focuses on the user interface framework: bottom navigation, Material 3 theme, mock campus data, and the first-pass screens for Home, Schedule, Groups, and Profile.

## Front-end structure

- `app/src/main/java/au/edu/unimelb/campuscompanion/MainActivity.kt` starts the Compose app.
- `ui/CampusCompanionApp.kt` owns navigation, the app bar, and the bottom navigation shell.
- `ui/screens/` contains the first front-end pages.
- `ui/components/` contains reusable UI building blocks.
- `ui/model/` contains mock models and demo data until backend, timetable, and sensing layers are connected.
- `ui/theme/` contains the Material 3 color and typography setup.

## Main screens

- Home: context-aware next-class card, travel status, upcoming classes, and group updates.
- Schedule: timetable list with sync/manual edit entry points.
- Groups: course groups, QR join action, chat/file status.
- Profile: account summary, permissions, privacy, and notification preferences.

## Next implementation steps

1. Connect Home to a real context engine state model.
2. Replace mock timetable data with Room/ICS data.
3. Wire Groups to Supabase repositories.
4. Add CameraX QR scanner screen and navigation route.
5. Add previews and UI tests once the first visual direction is stable.
