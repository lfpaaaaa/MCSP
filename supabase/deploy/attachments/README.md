# MCSP attachment upload repair package

Target project: `mbtgwmtgxlufyfcrzwto`. Branch: `integrate/context-engine-sensing-main`, baseline commit `086240d`.

Read-only diagnostics on 2026-10-14 confirmed that the production database used by the phone is missing `public.shared_files.allow_downloads`. This client version writes a download permission for every camera photo, gallery image, video and file, so the missing column causes uploads of all attachment types to fail. Camera photos remain on the phone, and failed uploads can be retried after the repair.

A project administrator must apply this package. It has not been applied in production. The SQL does not delete existing groups, messages or attachment records; it can be run repeatedly and rolls back on failure. It requires the project's base groups, memberships, profiles and `shared_files` tables to exist.

## 1. Repair the database

In the target project's Supabase Dashboard > SQL Editor, run `01-repair-attachments.sql` with database owner privileges.

The script adds attachment download permissions, the private `group-files` storage bucket, upload policies, file listing and preview/download authorization RPCs. It retains the 20 MiB limit per attachment. Only group members can upload to their own directory; when downloads are disabled, other members can only preview the file. Downloads are enabled by default for existing attachments. If the project does not yet have group nicknames, file listings use the user's display name.

Run `02-verify-attachments.sql`. All eight results must be `true`. This only verifies the database; the service deployment in the next step is also required.

## 2. Deploy the preview and download service

Run these commands from the extracted directory containing the `supabase/` folder, or from the repository root:

```sh
npx supabase@2.117.0 login
npx supabase@2.117.0 functions deploy group-file-url --project-ref mbtgwmtgxlufyfcrzwto
```

The included `supabase/config.toml` sets `verify_jwt = false` because the function itself calls Supabase Auth to validate the sign-in token, then checks group membership and download permissions as the caller. Do not remove authentication inside the function. Signed URLs use service credentials provided by the runtime; administrators do not need to send the service-role key to developers or include it in the Android app.

For deployment through the Dashboard, create or update the `group-file-url` Edge Function, copy the included `supabase/functions/group-file-url/index.ts`, and disable the gateway's legacy JWT check as described above. Keep the function's own Auth validation and permission checks.

## 3. Acceptance checks

In a group containing only test accounts, send a camera photo, gallery image, document and short video. Confirm that uploads complete, attachments appear in the chat and group file list, images/videos can be previewed, and files with downloads enabled can be saved.

Then verify with two test members: when the sender turns off "Allow others to download", the other member can preview but cannot download; the uploader can still download their own attachment; an account outside the group cannot access it. Media larger than 20 MiB should show a size-limit error. The document picker currently has a separate 10 MiB selection limit.

Finally, return to the original phone and tap Retry on the failed attachment cards. If the operating system has removed a source file, select it again and resend. Reinstalling the APK or granting additional gallery permissions cannot repair a missing server-side column.

## Migration history

This standalone repair script addresses a deployment gap and is derived from the repository's `20260925070000_group_files.sql` and `20261011170000_attachment_download_permissions.sql`. It does not modify `supabase_migrations` history.

If the project has consistently managed migrations through the CLI, first inspect `npx supabase@2.117.0 migration list --linked` and `npx supabase@2.117.0 db push --dry-run`, then apply missing migrations through the project's normal process. If applying this package manually, administrators should reconcile migration history only after comparing the existing schema with the complete effects of both migrations. Do not blindly rerun old non-idempotent migrations or reset the database.

## Client changes

The companion Android patch displays recognized missing attachment-service deployment errors as "Attachment sharing is not available on the server yet. Contact the app administrator, then retry." It preserves the Retry action on failed attachment cards. It does not expose unauthorized attachments through public links or ignore the user's download permissions.

Once the database and function deployments are complete, the original integration client can resume sending attachments. The companion APK improves the error message.

## Completed local validation (2026-10-14)

- All 777 JVM tests passed, and `assembleDebug` succeeded.
- The deployment SQL passed 90 database assertions across an existing schema, a schema missing the download-permission column, and repeated execution.
- All eight deployment checks passed with both download permissions and group nicknames missing.
- In local Supabase, three temporary test accounts successfully uploaded JPEG, PNG, TXT and MP4 files, saved their metadata, previewed identical content as group members, and downloaded files as uploaders. Downloads by other members were rejected when disabled, and access from outside the group was denied. Test data was cleaned up.
- Read-only production checks confirmed the missing column. No production migration or deployment was performed because project administration access was unavailable; an administrator must still complete production acceptance checks.
