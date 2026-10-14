# Shared delivery reliability verification

14 October 2026, Australia/Melbourne. Phase 0 on `feat/extra-features`, based on
`95443cb`. The validated source is the reliability commit containing this report.

## Implemented behavior

- Upload queue entries persist their UUID, owned storage path, consent and immutable private
  byte copy. Retries reconcile the same file ID; lost Storage or finalization replies never
  trigger deletion of a possibly committed object. Observing the durable file acknowledges
  its queue entry. Local bytes survive interruption and are removed after confirmation or
  explicit discard. Persistence failures do not publish a transfer that cannot be recovered.
- The uploader deletes through an authenticated server endpoint. A durable cleanup job
  precedes Storage API removal; metadata is removed afterward. Failure remains retryable in
  the attachment viewer. Completed jobs remain as tombstones for delayed retries. Cascading
  file deletion and group dissolution queue work without depending on surviving memberships,
  including uploads that have not acquired file metadata. Open chats also reconcile file
  metadata every 30 seconds while observed.
- Code, QR and NFC joins publish/cache the returned group before refreshing summaries.
  Failed refreshes preserve success; stale operations cannot publish into another account
  or a newer login session. Existing group role and activity values are retained.
- Chat recovery serializes live insertions with catch-up and advances a checkpoint only
  after successful history reconciliation. A fresh repository replays from its oldest
  cached confirmed row and reads the latest page to repair interrupted caches. The existing
  bounded catch-up policy keeps the latest window when over 500 messages were missed;
  older messages remain accessible through history paging. A failed snapshot fetch does not
  erase the cached timeline.

The implementation adds no voice recording, transcription, file-browser screen or compass.
The branch's context engine and sensing/reminder implementations were retained.

## Automated verification

The baseline Android unit suite passed before implementation. Final checks:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest
deno check --node-modules-dir=none supabase/functions/group-file-delete/index.ts supabase/functions/group-file-cleanup/index.ts
deno test --node-modules-dir=none supabase/functions/_shared/group-file-cleanup.test.ts
git diff --check
```

All **745** Android unit tests and app/instrumentation APK builds passed. Focused regressions
cover lost upload/finalization replies, immutable retry bytes after process restart, changed
consent rejection, queue persistence errors, draft acknowledgement/copy races, deletion retry
after restart, unauthorized deletion, deletion reconciliation, join refresh failure/account
changes, and live insertions during failed paginated chat recovery.

All 16 application migrations applied in an isolated local test database, using empty
Auth/Storage infrastructure copied from the running local Supabase schema. All 15 pgTAP test
files passed: **261 assertions**, including **30** new delivery/cleanup assertions. Fixtures
ran in rollback transactions. No application data or migration history was reset in the
existing `supabase_db_MCSP` database; the isolated database was removed afterward.

Both new Edge Function entries passed Deno type checking. All **11** cleanup handler tests
passed using mocked HTTP responses. They cover Storage failure, both committed and
uncommitted lost completion replies, completed-job retries, uploader/Auth denial, service-only
worker authorization, partial failure and the 50-job cap. Least-attempted jobs are selected
first so persistent failures cannot block all newer cleanup work. CI now runs these tests.

## Backend rollout and remaining device checks

The migration and functions were prepared and tested locally, but were not applied/deployed
to the app's active or hosted backend. Before installing the updated client, follow the
README's shared delivery setup: apply the migration, deploy `group-file-delete` and
`group-file-cleanup`, and schedule the service-only cleanup POST. Without a scheduled worker,
dissolution/orphan cleanup remains durably pending; uploader deletion attempts execute cleanup
immediately. Standard Supabase server secrets are sufficient; no service credential belongs
in Android configuration.

Instrumentation APK compilation is separate from execution. No physical-phone or two-account
UI rehearsal was performed for this increment. After backend rollout, verify two signed-in
members share one attachment across reconnect/restart; the uploader can delete/retry, another
member sees its removal, and outsiders/removed members are refused. Verify a code/QR/NFC join
remains visible when the next summary request fails. These device/active-backend checks remain
manual acceptance work; HTTP mocks do not certify physical object deletion on a hosted bucket.
