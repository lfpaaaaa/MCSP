# Group files and View join code verification

14 October 2026, Australia/Melbourne. Step 2 of the agreed delivery order (Phase 1 in the
implementation plan), on `feat/extra-features`, based on `ef254b6`. The verified source is
the feature commit containing this report.

## Delivered behavior

Settings lists Members, Group files, View join code, Share QR code and Invite with NFC in
that order. File browsing uses the group's existing attachments and protected viewer.
Search matches filenames/uploaders; All, Photos, Videos, Audio and Documents filters retain
newest-first ordering. Only the uploader sees Delete, with confirmation and recoverable
failure/Retry. Downloads and external opening retain server membership/consent checks.

The file catalog now exposes loading, authoritative empty results, saved/offline contents,
access denial and retry. Explicit Refresh and existing realtime/30-second reconciliation
pick up additions/deletions/updates. A denied catalog hides cached rows and suspends automatic
requests; successful manual refresh resumes them. Session generations isolate accounts and
reject old reads, upload confirmations and deletions after sign-out/account changes.

Fresh file catalogs also acknowledge the shared upload queue. This prevents a completed upload
from remaining in chat as “Uploaded · syncing…” if it finishes while Files is open and is
deleted before chat is reopened. Failed local acknowledgement has a separate Retry action;
confirmed IDs are retained through Activity recreation, and retry does not upload/delete again.

Pending fold/mute/nickname values stay above nested page switching and survive Activity
recreation. A fresh member lookup no longer overwrites an already loaded nickname draft.
Cancel dismisses it without saving; Save retains nickname-changed semantics. Timetable
memberships continue to hide invite/exit actions, including restored timetable identities.

Both server-group mappings derive join-code expiry from server creation plus 300 seconds.
The open group's code, expiry and timetable keys are saved/restored and cleared on closure.
Missing/malformed older saved expiry values remain unavailable. View join code shows the
six-character code, countdown and Copy, checks expiry again on Copy, and provides QR/NFC
alternatives. Reopening never renews expiry. The creation confirmation uses the same content.

No voice capture, transcription, compass, runtime permission, bucket or database migration
was added in this step. Context/sensing/reminder implementations were retained.

## Verification

The baseline unit suite passed before edits. Final verification commands:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class au.edu.unimelb.campuscompanion.ui.GroupSettingsRecreationInstrumentedTest au.edu.unimelb.campuscompanion.test/androidx.test.runner.AndroidJUnitRunner
git diff --check
```

All **774 unit tests** passed and both APKs built. New tests include 13 catalog cases for
loading/empty/offline/denial/recovery/account changes and stale read versus upload/deletion,
9 presentation/search/filter cases, 3 join-code expiry cases and 4 group mapping/restoration
cases. Existing delivery and sensing regression suites remain included.

The focused fixture suite passed **9 Android tests** on `emulator-5554`,
`sdk_gphone16k_arm64` (reported Android 17 / API 37). It runs production Compose settings
and browser content in the existing blank debug Activity, with real recreation and injected
members/files. It verifies:

- Edited drafts survive Members/Files → recreation → Back, then Save sends the edited values.
- Cancel after nested navigation never invokes Save; an unchanged nickname keeps its flag false.
- An expired code stays expired across recreation and offers QR/NFC; timetable invite/exit
  controls stay hidden while Members/Files remain available.
- Filename/uploader search and Photos filtering work; recipient Delete controls remain hidden.
- A failed explicit refresh clears after automatic catalog recovery, without a second tap.
- The actual restored upload queue retains its item for cached data, acknowledges/discards
  after fresh metadata, then stays empty after uploader deletion before returning to chat.

Fixtures capture settings callbacks and compare existing timetable/group/reminder preferences
before/after; installation used `-r` and no app data was cleared. Fixture repositories do not
contact a backend or sign accounts in/out. Search/filter/deletion are fixture UI evidence,
not a real two-account sharing rehearsal or physical microphone/compass testing.

## Remaining active-backend acceptance

This step adds no backend rollout. Apply/deploy/schedule the shared delivery migration and
functions from Step 1 before testing real uploads/deletions; its existing setup remains a
dependency. No active/hosted backend was changed during this step.

On two signed-in accounts after backend rollout, verify shared files appear/disappear on both
devices, protected previews/downloads enforce consent, uploader deletion retries finish,
and removed members/outsiders are refused. Check code/QR/NFC joining against the server's
five-minute boundary. These real-account checks remain manual acceptance work.
