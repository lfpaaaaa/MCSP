BEGIN;

SELECT plan(13);

-- Fixtures, created as the database owner ------------------------------------

INSERT INTO auth.users (id, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
VALUES
    ('a1000000-0000-4000-8000-000000000001', 'alice@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Alice"}'::jsonb, now(), now()),
    ('b2000000-0000-4000-8000-000000000002', 'bob@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Bob"}'::jsonb, now(), now()),
    ('c3000000-0000-4000-8000-000000000003', 'carol@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Carol"}'::jsonb, now(), now());

INSERT INTO public.groups (id, name, course_code, created_by)
VALUES ('e5000000-0000-4000-8000-000000000005', 'Mobile team', 'COMP90018', 'a1000000-0000-4000-8000-000000000001');

INSERT INTO public.memberships (group_id, user_id, role)
VALUES
    ('e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 'owner'),
    ('e5000000-0000-4000-8000-000000000005', 'b2000000-0000-4000-8000-000000000002', 'member');

INSERT INTO public.shared_files (id, group_id, uploader_id, file_name, mime_type, size_bytes, storage_path, created_at)
VALUES
    ('d1000000-0000-4000-8000-000000000001', 'e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 'older.pdf', 'application/pdf', 1000,
     'e5000000-0000-4000-8000-000000000005/a1000000-0000-4000-8000-000000000001/object-1', '2026-09-25 01:00:00+00'),
    ('d2000000-0000-4000-8000-000000000002', 'e5000000-0000-4000-8000-000000000005', 'b2000000-0000-4000-8000-000000000002', 'newer.pdf', 'application/pdf', 2000,
     'e5000000-0000-4000-8000-000000000005/b2000000-0000-4000-8000-000000000002/object-2', '2026-09-25 02:00:00+00');

-- Bucket and storage policies ---------------------------------------------------

SELECT ok(
    (SELECT NOT public FROM storage.buckets WHERE id = 'group-files'),
    'the group-files bucket is private'
);

SELECT is(
    (SELECT file_size_limit FROM storage.buckets WHERE id = 'group-files'),
    20971520::bigint,
    'uploads to the bucket are limited to 20 MB'
);

SELECT is(
    (
        SELECT count(*)
        FROM pg_policies
        WHERE schemaname = 'storage'
          AND tablename = 'objects'
          AND policyname IN (
              'Members can read group files',
              'Members can upload to their own group folder',
              'Uploaders can delete their own group files'
          )
    ),
    3::bigint,
    'storage policies cover reading, uploading and deleting'
);

-- Members ----------------------------------------------------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT ok(
    private.is_member_of_object_group('e5000000-0000-4000-8000-000000000005/b2000000-0000-4000-8000-000000000002/object-3'),
    'members can reach objects in their group''s folder'
);

SELECT ok(
    NOT private.is_member_of_object_group('not-a-group/b2000000-0000-4000-8000-000000000002/object-3'),
    'paths outside a group folder are refused without an error'
);

SELECT is(
    ARRAY(SELECT file_name FROM public.group_files('e5000000-0000-4000-8000-000000000005')),
    ARRAY['newer.pdf', 'older.pdf'],
    'files are listed newest first'
);

SELECT is(
    (SELECT uploader_name FROM public.group_files('e5000000-0000-4000-8000-000000000005') WHERE file_name = 'older.pdf'),
    'Alice',
    'the list shows who uploaded each file'
);

SELECT lives_ok(
    $$INSERT INTO public.shared_files (group_id, file_name, mime_type, size_bytes, storage_path)
      VALUES ('e5000000-0000-4000-8000-000000000005', 'mine.pdf', 'application/pdf', 10,
              'e5000000-0000-4000-8000-000000000005/b2000000-0000-4000-8000-000000000002/object-3')$$,
    'members can register files in their own folder'
);

SELECT throws_ok(
    $$INSERT INTO public.shared_files (group_id, file_name, mime_type, size_bytes, storage_path)
      VALUES ('e5000000-0000-4000-8000-000000000005', 'fake.pdf', 'application/pdf', 10,
              'e5000000-0000-4000-8000-000000000005/a1000000-0000-4000-8000-000000000001/object-4')$$,
    '42501',
    NULL,
    'members cannot register files in someone else''s folder'
);

-- Non-members ------------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);

SELECT ok(
    NOT private.is_member_of_object_group('e5000000-0000-4000-8000-000000000005/c3000000-0000-4000-8000-000000000003/object-5'),
    'non-members cannot reach the group folder'
);

SELECT throws_ok(
    $$SELECT * FROM public.group_files('e5000000-0000-4000-8000-000000000005')$$,
    '42501',
    'not_a_member',
    'non-members cannot list the files'
);

RESET ROLE;

-- Function privileges ----------------------------------------------------------

SELECT ok(
    NOT has_function_privilege('anon', 'public.group_files(uuid)', 'EXECUTE'),
    'anonymous users cannot list files'
);

SELECT ok(
    NOT has_function_privilege('anon', 'private.is_member_of_object_group(text)', 'EXECUTE'),
    'anonymous users cannot call the storage helper'
);

SELECT * FROM finish();
ROLLBACK;
