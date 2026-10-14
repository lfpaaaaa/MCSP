# Voice messages verification

14 October 2026, Australia/Melbourne. Step 3 of the agreed delivery order (Phase 2 in
`extra-features-implementation-plan.md`), on `feat/extra-features`, following `efa6548`.
The team deferred all transcription: no transcript controls, jobs, worker or recognition
service are part of this increment.

## Implemented behavior

- Microphone beside the composer, Start/Stop, monotonic elapsed timer, actual peak-amplitude
  input sampled every 100 ms, a relative level meter, and a ten-minute cap.
- Native mono AAC in M4A at 96 kbps. Captures must contain a readable audio packet and at least
  one second of audio. A small AAC frame-duration tolerance clamps a valid limit recording's
  metadata to ten minutes; substantially longer or damaged files cannot be staged.
- Stop opens playback/seek review with Record again, Cancel/discard and Use recording.
  Use recording saves a composer draft; only Send starts upload. Download consent is saved
  with the draft and queue metadata. The staged card uses full width and bounded scrolling,
  keeping Send outside the scrolling preview on small screens and at large font sizes.
- ON_STOP and disposal finalize/release capture. Valid stopped drafts survive recreation and
  restart; returning never starts the microphone. Permission and device callbacks carry
  generation tokens so stale callbacks cannot restart or stop another recording.
- Private recording ownership is scoped to account/group. Review and Staged drafts remain
  local; Queued originals stay until confirmation or deliberate failed-upload removal.
  Stable recording/upload IDs deduplicate repeated handoff and metadata registration.
- Queue ownership takes effect immediately after durable enqueue, even when saving the
  local Queued marker fails. Startup hydrates ownership from every account's restored queue
  and cleans only Queued recordings absent from that queue. An expected-owner check prevents
  an old screen from sending a former account's private recording as a newly signed-in user.
- Audio attachments show voice/audio labels and duration, and open protected in-app
  play/pause/seek controls. Preparation/decoder failures offer Retry. Playback pauses on
  background/focus loss, never resumes automatically, and permits one active audio preview.
  Audio previews use membership authorization; original export keeps uploader consent checks.
- Microphone permission appears in Profile and is requested only on Start. Existing location,
  background-arrival and activity permissions retain their behavior.

The foreground capture and native-stop behavior follow
[Android MediaRecorder guidance](https://developer.android.com/media/platform/mediarecorder)
and the [MediaRecorder reference](https://developer.android.com/reference/android/media/MediaRecorder).
Playback handles interruptions using
[Android audio focus guidance](https://developer.android.com/media/optimize/audio-focus).

## Backend rollout

Apply the additive `20261014100000_voice_recording_metadata.sql` after shared delivery
reliability, to the same backend configured in the Android app. It adds nullable duration and
an explicit voice flag, validates audio/ten-minute metadata, preserves all catalog response
fields and nickname handling, and extends finalization with defaults compatible with older
seven-argument callers. Immutable metadata is checked on retries. No transcription schema or
new Edge Function is added. Existing preview/deletion endpoints and cleanup scheduling remain
required; see README.

No live local or hosted database has been reset or migrated by this verification. The database
suite ran in a temporary schema-only copy with every app migration applied; test transactions
rolled back and the scratch database was dropped.

## Automated verification

- **824 JVM tests passed**, zero failures/errors. Includes 26 recording controller/store
  regressions, playback/focus/stale callbacks, actual-level states, metadata/cache compatibility,
  durable queue deduplication and expected-account rejection, plus existing sensing/reminder tests.
- `testDebugUnitTest`, `assembleDebug` and `assembleDebugAndroidTest` succeeded with Java 17.
- **19 focused Android tests passed** on `emulator-5554` (Android 17 / API 37): six isolated
  production voice UI tests, two native playback tests, two native microphone/AAC tests, and
  all nine Step 2 settings/browser recreation tests. Debug APKs installed with `-r`; app data
  was retained. RECORD_AUDIO was temporarily granted for native tests and restored to its
  original denied state afterward.
- Native capture produced a readable M4A/AAC clip with valid duration/packet metadata and
  platform decoding. Immediate native Stop produced an unusable clip that could not stage.
  Playback exercised a generated WAV and decoder failure/retry; no external download occurred.
- UI fixtures exercised Stop/Use without upload, saved consent and stable Send, Cancel,
  actual Activity ON_STOP/recreation, denied/revoked-input state handling, and a 320 dp composer
  at font scale 2. Capture in these UI fixtures used a fake device writing a playable local WAV;
  the separate native tests exercised MediaRecorder. Repositories were isolated, with no login,
  sign-out or backend write. Permission-failure fixtures did not change system grants.
- Visual QA inspected recorder review and normal/compact large-text staged composer
  screenshots. Readiness and download consent appear above playback; the staged card adapts
  to available height and scrolls while Send remains reachable. The six voice UI tests passed
  again after this final layout adjustment. Screenshots are temporary QA artifacts rather than application assets.
- `git diff --check` passed.

Initial UI checks exposed readiness text below the capped preview viewport; it now appears
above playback. A new cleanup test exposed a canonical-path alias mismatch (`/var` versus
`/private/var`); reconciliation now enumerates canonical paths. Both passed the final rerun.

Database: **288 pgTAP assertions passed**, across 16 test files after all 17 migrations.
The 27 new voice assertions cover duration boundaries, audio type, immutable retry metadata,
old-call compatibility, member visibility, outsider rejection and preview/download consent.

## Remaining integrated acceptance

The emulator validates Android APIs and fixture flows; it does not establish audible speech,
physical microphone sensitivity, phone-lock behavior on a particular handset, or two-account
sharing against a deployed backend. Before the demo, on a real Android phone and the configured
backend:

1. Grant/deny/retry microphone permission; record speech at quiet/loud levels, listen/seek,
   cancel/re-record, stage without upload, then Send. Check Profile permission status.
2. Lock the phone, switch apps, rotate and leave chat while capturing. Each valid clip must
   stop and remain reviewable, with no automatic recapture. Kill/restart with Review/Staged
   and interrupted Capturing drafts; corrupted captures must never silently send.
3. Check the actual ten-minute cap, immediate Stop, audio interruption and unavailable audio.
4. Send with download consent on/off. A second current member can preview; original export
   follows consent, outsiders/removed members are denied. Audio appears in Group files.
5. Interrupt upload/response and retry after leaving chat/restarting; confirm one attachment,
   retained retry bytes and eventual private cleanup. Check another account cannot send,
   edit or discard the former account's pending recording.
6. Exercise uploader deletion and the deployed cleanup schedule. No transcript is expected.
