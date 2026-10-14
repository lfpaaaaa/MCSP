# Recording and transcription

In a group chat, tap the **microphone button beside the message field**. Start/stop, listen, then **Use recording**.
The recording is staged with the other attachments: choose **Allow others to download**, then tap Send.
Recordings are AAC/M4A, mono, 96 kbps, with a ten-minute limit. Switching apps or locking the screen stops capture and preserves the recording for review. This is foreground recording, not a background lecture recorder.

Open an uploaded audio attachment and tap **Generate transcript**. Only its uploader can request/retry transcription; every current group member can view the saved result. Queued/processing/ready/failed states live in Postgres, so closing the app or signing out does not lose a job. The local worker automatically detects the spoken language. Transcripts can contain recognition errors. Download restrictions still apply to the recording; transcript text selection is offered only to users allowed to download.

**Group settings → Group files** opens an independent page with filename/uploader search, All/Photos/Videos/Audio/Documents filters, newest-first order, in-app preview and uploader-only deletion. Deleting a file also deletes its transcript. Previously cached file metadata is available offline; loading uncached media/transcripts requires the backend.

## Start the local worker

Apply `supabase/migrations/20261013090000_file_transcripts.sql` before using this version. On a normal migration-tracked local instance, use `supabase db push --local`; do not reset a database containing user data.

From the repository root:

```sh
uv venv .local/transcription-venv --python 3.12
uv pip install --python .local/transcription-venv/bin/python -r services/transcription/requirements.txt
WHISPER_MODEL_DIR="$PWD/.local/whisper-models" \
  .local/transcription-venv/bin/python services/transcription/worker.py --local
```

The first run downloads the multilingual Whisper `base` model. No paid API key is needed. Keep this process running while transcription is needed; with it stopped, requests remain queued. `--local` reads local Supabase credentials from `supabase status -o json` without printing them. The CPU worker uses four threads. Audio is downloaded from the private group bucket to a temporary file and removed after processing. It is not sent to a third-party speech API. Model downloads use Hugging Face.

`WHISPER_MODEL` selects a different faster-whisper model. Pinning PyAV is necessary because newer PyAV versions removed an API used by faster-whisper 1.2.1. `--once` handles at most one job and exits, useful for controlled tests.

## Hosted deployment

Run the worker on an always-on server with the same requirements and `SUPABASE_URL` / `SUPABASE_SERVICE_ROLE_KEY` in its server environment; omit `--local`. Never put the service-role key in the Android app. Apply the migration to that backend separately. There is no new public worker HTTP port and no cloud speech API key.

Jobs are leased transactionally with `SKIP LOCKED`; interrupted jobs become claimable after thirty minutes. A lease identifier prevents an old worker from overwriting a newer result. Users cannot claim jobs or write recognition results. Files are limited to 20 MB by the existing upload layer and audio longer than thirty minutes is rejected by the worker.

Validation: `supabase test db supabase/tests/database/file_transcripts.test.sql`, Android unit tests, and an actual synthetic-speech upload/transcription/read-after-login/delete integration check. Microphone capture quality requires a physical Android device; emulator UI tests can use silent input without enabling the computer microphone.


## Transcript editing and search

Apply `supabase/migrations/20261013220000_file_transcript_edits.sql` to the same backend used by
the app (locally: `supabase db push --local`, without a database reset). Open an audio attachment,
then **Read and search transcript**. Members can search that recording's full transcript with
case-insensitive literal matching, highlighting and previous/next results. Long transcripts are
split into display chunks without truncating the searchable text. Download-disabled members
can read/search, but text selection remains disabled.

Only the uploader sees **Edit**. Save updates Postgres through `update_file_transcript`, with a
revision check to prevent stale drafts overwriting another device's correction. Retrying an
already-applied identical update is safe. Network errors leave the editor draft intact; leaving
with unsaved changes asks before discarding. **Reload saved version** refreshes after a conflict.
Drafts survive ordinary activity recreation, but only a successful save is durable across logout.
Members see saved corrections when reopening or refreshing the transcript. Recognition jobs
and audio files are unchanged. Database permission/conflict tests are in
`supabase/tests/database/file_transcript_edits.test.sql`; search/serialization tests run with
`./gradlew testDebugUnitTest`.
