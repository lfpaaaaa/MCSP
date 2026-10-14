# Group settings voice messages and classroom compass implementation plan

14 October 2026, Australia/Melbourne. Target: the existing `feat/extra-features` branch at `95443cb80ade75c632baac89a245c5539bc2dd8c`. Reference implementation: local `main` at `f1a251e`. This is an implementation plan; no application code, migrations or deployments are changed by it.

Implement Group files and View join code first, then foreground voice recording and playback. On 14 October 2026 the team deferred automatic transcription until a later request; it is not part of the current voice increment. The team has also deferred the classroom compass (Step 5) until a later request. Adapt the relevant components from `main` while retaining the current branch's context engine, initial-location acquisition, reminder preferences, account isolation and sensing lifecycle protections.

## Scope and project plan alignment

| Work | Intended result | Scope |
| --- | --- | --- |
| Group settings additions | Members → Group files → View join code → Share QR, followed by existing fold/mute/nickname settings and exit action. | Required. Preserve current role and timetable-group visibility rules. |
| Group file browser | Search/filter the group's existing attachments, preview/download with current permissions and let uploaders delete their own files. | Required. Reuse existing cloud files rather than creating a second sharing system. |
| Chat voice messages | Microphone beside the composer; Start/Stop, timer, live input meter, review, Cancel and Use recording; then Send. | Required. Maximum ten minutes, matching the supplied recording image. |
| Speech to text | Automatically queue a transcript when a recorded clip is sent; display durable progress and the saved transcript to group members. | Deferred by the team on 14 October 2026; retain the design for later work. |
| Classroom compass | Visible arrow and readable direction toward the selected teaching building, using live heading and location. | Deferred by the team; retain the design for a later request. |

The project plan makes recording with speech to text, a live recording meter, a transcript viewer and a compass arrow P1 work. It describes **automatic transcription**. The screenshot and `main` currently use **Generate transcript** after upload. The team has now deferred all transcription. The current recording help describes sharing an audio clip only, with no automatic enqueue, Generate, Retry transcript or transcript viewer. The proposed transcription design below is retained for a future request.

The proposed recording experience is a shared voice clip or meeting excerpt, not a live voice call. Recording stops when the app leaves the foreground or the screen locks. Extending this to long background meeting capture is a separate feature.

## Integration approach

Both settings additions, recording/playback, transcription infrastructure and a compass screen already have source implementations on local `main`. They are starting points, not verified deliverables on this branch.

Selectively adapt components and their tests. `main` differs across 211 files, including substantial context/sensing changes and a broad localization refactor. Replacing shared files wholesale could remove the current branch's validated behavior. In particular:

- Keep the current `TravelEngine`, sensing service, account handling, permission behavior and monotonic observation timestamps.
- Adapt screen strings and errors to the current branch; add the needed resources without importing the entire `UiText` conversion.
- Change shared files such as `CampusCompanionApp`, `AppRepositories`, the manifest and permission model only at the required integration points.
- Keep existing tests. Porting a screen must not remove Step 6 journey, post-class, permission or initial-location coverage.
- Use additive database migrations. Check applied migration history before reusing existing migration files; do not rewrite deployed migrations or reset a database containing user data.

## Phase 0 Make shared delivery reliable

The earlier verification identified defects that the new features would expose more often. `main` still contains the problematic file handling, so copying its UI will not fix them.

1. **Give uploads a stable identity.** Persist an upload ID and storage path with each queue item. Retry the same upload rather than generating a new path on every attempt. Reconcile metadata by that identity after a lost response; a committed metadata insert must not cause the valid object to be deleted. Preserve local recording bytes until durable success or explicit discard.
2. **Make deletion recoverable.** Add an owner-authorized server deletion path that coordinates object and metadata removal. Keep a durable cleanup record for operations that cannot finish, including group dissolution, and retry through the Storage API. Do not weaken the existing download-consent policy to make cleanup possible. Expose deletion failure/retry in the UI.
3. **Preserve a successful join locally.** Publish/cache the group returned by the join RPC before attempting a best-effort summary refresh. QR/code/NFC success must not claim the group is visible while its local list is empty.
4. **Fix reconnect catch-up.** Use a completed synchronization checkpoint rather than the newest row that a realtime insert happened to put in the cache. Serialize/checkpoint recovery so a new live message cannot skip an older missed message.

Primary touchpoints: `AttachmentUploadQueue`, `DefaultFileRepository`, `FileRepository`, `FileRemoteDataSource`, `SupabaseFileDataSource`, `DefaultInviteRepository`, `DefaultGroupRepository` and `DefaultChatRepository`, plus focused cleanup/finalization backend endpoints or migrations.

**Completion check:** reproduce and then pass the four earlier failure scenarios: lost metadata response, object deletion after metadata removal, join followed by failed refresh, and a realtime insert arriving during reconnect catch-up. Test retry after process restart and ownership/membership restrictions. Failed cleanup remains observable and retryable.

## Phase 1 Add the two group settings controls

### Group files

Adapt `GroupFilesScreen.kt`, `GroupFilesPresentation.kt` and their presentation tests from `main`.

- Put **Group files** immediately below Members.
- For the smallest integration, reuse the current settings screen's nested Members-page pattern for Files. Keep pending fold/mute/nickname values above the page switch, so opening Files and returning does not lose edits. A separate route is also acceptable if it explicitly preserves that draft and handles missing groups.
- Observe `AppRepositories.files` and reuse `ChatAttachmentViewer` for protected previews/downloads.
- Support filename/uploader search, All/Photos/Videos/Audio/Documents filters and newest-first ordering.
- Provide loading, empty, no-results, cached/offline, permission-denied and retry states.
- Show Delete only for the uploader and use Phase 0's reliable deletion path. Revalidate server access for actions even when metadata is cached.
- Refresh after additions, deletions and updates, including changes made on another device. Use reliable invalidation or an explicit refresh/reconciliation path; do not assume the current INSERT-only subscription covers deletion events.

The catalog itself needs no new bucket or separate file schema. Voice/transcript metadata and reliable deletion may require additive migrations in their respective phases.

### View join code

Adapt `GroupJoinCodeDialog.kt` and the expiry helpers from `main`.

- Put **View join code** before Share QR, using the same custom-group visibility rule.
- Carry the existing group's join code and expiry into `CourseGroup` and both repository-to-UI mappings.
- Use the original server creation timestamp plus the existing five-minute lifetime; preferably expose a server-computed expiry if the backend contract is extended. Reopening the dialog must never restart that lifetime.
- Display the six-character code, remaining time, Copy and an expired state with a QR alternative. Do not offer a cosmetic refresh that implies a new server code has been created.
- Preserve code/expiry when restoring an open group and clear them when closing it; tolerate absent fields in older saved state.

Keep timetable groups automatically joined and hide their invite controls. Preserve owner Dissolve versus member Leave, nickname saving, mute behavior and Cancel semantics.

Primary files: `GroupSettingsDialog.kt`, `GroupMembersSection.kt` or a small settings-page state model, `CampusCompanionApp.kt`, `CampusModels.kt`, `GroupJoinCode.kt`, the two added screens and the necessary strings.

**Completion check:** Settings → Files → preview/download → Back preserves pending settings. Cancel discards those edits; Save persists them. Join-code tests cover just before expiry, the exact 300-second boundary and Activity recreation. A second member sees new/deleted attachments; outsiders and removed members cannot access them.

## Phase 2 Add voice recording and playback

Adapt the following source from `main`: `RecordingDialog.kt`, `RecordingLevelMeter.kt`, `RecordingLevel.kt`, `AudioAttachmentPlayer.kt` and `RecordingFiles.kt`. Integrate them through `GroupChatScreen`, `ChatAttachments`, the existing upload queue and small `AppRepositories` changes.

### User flow

1. Tap a microphone beside the chat composer.
2. Request microphone access only when recording is requested. Denial leaves chat usable and provides an explicit retry/settings path.
3. Show Start recording, an elapsed timer and a live microphone level. While recording, offer Stop.
4. Stop, listen/seek, then choose Record again, Cancel or Use recording.
5. Use recording stages the clip in the composer. It does not upload immediately. Show download consent. Sending shares the audio clip only; transcription is deferred.
6. Send through the existing queue. Show progress, failure and retry like other attachments.
7. Show an audio attachment with sender, duration, timestamp and delivery state. Tapping it opens in-app play/pause/seek controls. Include Audio in Group files.

Use AAC in an M4A container, mono at approximately 96 kbps, with a ten-minute duration cap and the existing 20 MB upload limit. Reuse platform `MediaRecorder` and `MediaPlayer`; this increment does not require a new audio library. Construct `MediaRecorder(context)` on API 31+ and use the no-argument constructor on API 26–30.

Add `RECORD_AUDIO`, microphone permission status/settings support and a narrowly scoped FileProvider path for private `recordings/` files. Preserve all current location/background-arrival/activity permission behavior. Android requires runtime microphone permission, and foreground-only recording fits its microphone access constraints. [Android MediaRecorder guidance](https://developer.android.com/media/platform/mediarecorder).

### Lifecycle and delivery design

Extract recording ownership/state into a testable controller rather than keeping all recorder operations inside the composable. Model Ready, Requesting permission, Recording, Review and Failed; staging/uploading remain attachment-queue states.

- Use a monotonic clock for duration and an idempotent stop/finalize operation for manual stop, ten-minute completion, errors and lifecycle callbacks.
- Sample actual peak amplitude roughly every 100 ms, normalize/smooth it for a relative level meter, and label inactive input separately. Do not animate fabricated speech or present it as a calibrated decibel reading.
- Stop/release on background, screen lock, cancellation, account/group change or controller disposal. Never restart automatically when the app returns.
- Preserve a valid stopped draft through ordinary recreation. On process restart, validate any persisted draft; an interrupted, unfinalized file may be unusable and must not be sent silently.
- Ignore a permission grant or recorder callback after its chat/dialog has closed.
- Keep staged/queued local bytes until success or deliberate discard. Do not remove another account's pending clip. Clean up abandoned private files without deleting queued drafts.
- Pause/release playback on background and resource disposal; avoid multiple simultaneous players and handle audio interruptions.
- Provide accessible control labels, readable elapsed time and large-font layouts. Announce recording state changes, not every meter/timer update.

**Completion check:** a real phone records audible speech, responds to quiet/loud input, stops at ten minutes and preserves a stopped draft after locking/switching apps. Denial, interruption, immediate Stop, Cancel, rotation, upload interruption and retry behave predictably. A second member receives and plays exactly one clip, including after the sender leaves chat.

## Phase 3 Deferred automatic transcription

The current Step 4 instead hides NFC join/invite buttons on the Groups page and group settings,
including the join-code fallback, following the team's inconsistent results across phones.
QR and code joins remain available; NFC internals are retained for a later fix.

This phase is deferred by the team and is not enabled or deployed as part of voice recording. When resumed, reuse the core `file_transcripts` schema/RPC/worker design from `main`, adapting `TranscriptRepository` and a simple transcript status/viewer. Transcript search/edit and their extra migration are optional later work.

### Durable automatic flow

Persist the attachment kind, duration and automatic-transcription choice with the staged recording and upload queue. Use backward-compatible defaults for older rows/queue items. Mark app-recorded clips explicitly so arbitrary uploaded audio is not unexpectedly transcribed.

Finalize file registration and transcript-job creation in one server transaction, for example through an idempotent `finalize_voice_attachment` RPC or a validated metadata trigger. Audio bytes are uploaded first; the transaction then creates/reuses the file record and its single job. Validate current membership, uploader ownership, the owned storage path, object existence and stored size/type; do not trust arbitrary client paths or metadata. The trusted worker must validate actual decodable audio and duration before transcription. Do not rely on a one-off Android callback after upload: the sender may close the app at that moment.

The durable job flow is `queued → processing → ready / failed`. Current members can read it; only authorized uploaders can request/retry. The trusted worker alone claims/completes jobs. Keep leases, stale-worker rejection, membership checks, deletion cascade and per-file idempotence. Bound audio size/duration and worker workload. A failed or delayed transcript must not block playing the successfully uploaded clip.

The viewer shows queued/processing status, readable transcript text or a useful failure/retry state. Persist results on Supabase so they survive closing chat and signing in again. Restrict original-audio download and transcript export according to the chosen sharing policy; download-disabled members can still receive content needed for permitted in-app viewing.

### Speech engine recommendation

For stored ten-minute clips, adapt `services/transcription/worker.py` and its pinned dependencies from `main`. This worker downloads private audio, transcribes with faster-whisper and stores the result. The existing setup uses Python 3.12, `faster-whisper==1.2.1` and `av==15.1.0`; validate those pins when porting. It needs an active local or hosted process and an initial model download; merging Android code alone does not enable transcription. Hosted configuration supplies `SUPABASE_URL` and server-only `SUPABASE_SERVICE_ROLE_KEY`; `--local` uses the repository's Supabase CLI/status command. Apply migrations to the same backend the app uses.

This is an explicit architecture update from the project plan's proposed on-device `SpeechRecognizer` with Edge Function fallback. The capability remains automatic speech to text. Android's recognizer is not intended for continuous recognition, and saved-audio input depends on newer APIs and recognizer support; do not assume a ten-minute, on-device path works across the current minimum API 26. [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer), [RecognizerIntent audio input](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE).

If the assessment requires the original on-device-first architecture specifically, add a separately tested, capability-gated provider behind a transcription interface, with the durable server worker as fallback. Do not run competing microphone capture pipelines or claim unsupported local recognition.

Required backend work: reuse/adapt `20261013090000_file_transcripts.sql` according to migration history, add new recording metadata/automatic-enqueue migration(s), adapt the worker and provide local/hosted run instructions. Do not bring unrelated pins or transcript-editing migrations into this increment.

**Completion check:** Send → sender closes app → second member plays audio and later reads the automatically generated transcript. One upload produces one job. Worker outage leaves a durable queued state; queued jobs resume when it starts, while interrupted processing jobs currently become reclaimable after the 30-minute lease expires. Test those cases separately, along with stale leases, duplicate finalization, removal/dissolution during processing, unauthorized access and uploader retry. Run a known speech fixture through the actual worker, then assess real recorded speech on a phone.

## Phase 4 Deferred classroom compass

The team deferred Step 5 until a later request. This design is retained for future work and
is excluded from the current Step 6 integration scope.

Adapt `DestinationCompass.kt`, `ClassCompassScreen.kt` and the pure compass tests from `main`, using the current branch's building lookup, location tracker, permission handling and Google Maps callback.

### Screen and calculation

- Add **Direction to building** beside navigation in Home and Schedule. Resolve the selected occurrence by a safely encoded session ID and look up its current building. Handle removed classes and unknown/online venues.
- Show class/course, building and room details, a large arrow, a readable relative direction and approximate straight-line distance. Keep Navigate with Google Maps available.
- Compute the geographic bearing from the current fix to the building. Use `trueHeading = normalize(magneticHeading + declination)` and `relativeAngle = normalizeSigned(bearing − trueHeading)`, where the normalizations produce 0–360° and −180–180° respectively.
- Remap the rotation matrix for all display rotations before deriving the screen arrow; do not rotate an azimuth scalar alone. After rotation, wait for a fresh sample tagged with the current display rotation. Animate the shortest turn across north. Android provides the relevant [coordinate remapping](https://developer.android.com/reference/android/hardware/SensorManager#remapCoordinateSystem(float[],%20int,%20int,%20float[])) and [magnetic declination](https://developer.android.com/reference/android/hardware/GeomagneticField#getDeclination()) APIs.
- Use proposed UI bounds of heading age ≤5 seconds, location age ≤30 seconds using monotonic time and location accuracy ≤50 m, consistent with the current sensing freshness approach. Reevaluate expiry even when no new event arrives.
- Show permission/location-off, waiting for fix, missing sensor, waiting for heading, calibration/low accuracy and hold-flat states. Hide the arrow when evidence is unusable.
- Near the building, replace the arrow with “Near teaching building; check the entrance and room number.” Existing coordinates do not identify entrances. A proposed 75 m display threshold must not mark attendance or change the context engine's ARRIVED state.

Call it a classroom/building compass, with clear copy that coordinates identify the teaching building. It cannot give indoor floor/room routing or a safe walking route through walls and roads.

### Preserve sensing ownership

Extend the current detector carefully to expose availability, magnetic accuracy, tilt and display orientation while preserving activation checks, ordering, finite-vector validation, freshness and main-thread serialization. Keep `LocationSample.elapsedRealtimeNanos`.

Prefer a separate UI/display sample or adapter so display-axis correction cannot silently change the raw heading semantics used by movement fusion. A screen-owned instance may reuse the detector class; it must not start/stop the service-owned instance. Run its listeners and any foreground location request only while the screen is resumed, then release them.

Do not copy `main`'s detector/tracker wholesale: their APIs differ and they lack several of this branch's protections. Adapt the screen to the current Unit-returning start APIs or add observable status, rather than replacing tracking implementations. Opening the compass must not alter the reminder target, selected transit journey, sensing generation or notification suppression.

Primary changes: new compass model/screen/tests; small detector/display-metadata extensions; Home/Schedule callbacks; a `class_compass/{sessionId}` route in `CampusCompanionApp`; required strings. No backend or new runtime permission is needed beyond existing foreground location permission.

**Completion check:** unit tests cover cardinal directions, positive/negative declination, all four rotations, north wrap, stale/future samples and low accuracy. Device checks cover denied location, missing sensors, calibration, background/resume and repeated opening. Verify known outdoor building directions on a phone and confirm opening/closing the compass leaves reminder/sensing behavior unchanged.

## Delivery and verification order

| Increment | Deliverable | Gate before proceeding |
| --- | --- | --- |
| 0 | Upload, cleanup, join and reconnect reliability fixes | Previously reproduced regressions pass without weakening authorization. |
| 1 | Group files and View join code | Settings draft, expiry, two-member browser and permission checks pass. |
| 2 | Voice recording, delivery and playback | Real microphone capture, lifecycle and duplicate-free retry pass. |
| 3 | Deferred: automatic transcript jobs, viewer and worker instructions | Resume only after a future request; no transcription dependency for voice messages. |
| 4 | Deferred: classroom compass | Resume only after a future request; physical direction and sensing checks apply then. |
| 5 | Final integration evidence | Clean builds, relevant full suites and signed-in two-device rehearsal pass. |

Use separately reviewable commits for these increments. Before implementation, rerun the current baseline; after each increment run its focused tests and integration checks. At final integration run `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest`, the database tests against isolated fixtures and the relevant Android suites. Distinguish emulator/fake checks from real microphone, physical compass and hosted-backend acceptance. Preserve app data during device testing and do not reset the development database.

The final rehearsal is: create a custom group → view code/QR → second account joins → browse a shared file → record/review/send voice → receiving account plays it → delete with correct permissions. Transcript and compass acceptance are deferred until their phases are resumed. A future compass rehearsal should open it from Home and Schedule while departure sensing runs and confirm the correct building, Maps action and unchanged reminders.

Update README and permissions/help text, list required migrations and worker configuration, and record the tested commit/device/backend. The written plan's P1 features should be final-demo claims only after those integrated checks pass.

## Deferred work

Automatic transcription, transcript UI, worker deployment and the classroom compass are deferred by the team. Biometric protection is a separate P1 feature; microphone permission and download consent do not implement it. This increment also excludes live voice calls, background lecture recording, recordings over ten minutes, transcript editing/search, pinned messages and a broader navigation/localization rewrite. File-catalog pagination and Room file-cache migration can be scheduled separately; the first browser uses the current repository/cache contract.
