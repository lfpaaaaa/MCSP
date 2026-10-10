BEGIN;

SELECT plan(24);

-- Fixtures, created as the database owner ------------------------------------

INSERT INTO auth.users (id, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
VALUES
    ('a1000000-0000-4000-8000-000000000001', 'alice@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Alice"}'::jsonb, now(), now()),
    ('b2000000-0000-4000-8000-000000000002', 'bob@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Bob"}'::jsonb, now(), now()),
    ('c3000000-0000-4000-8000-000000000003', 'carol@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Carol"}'::jsonb, now(), now()),
    ('d4000000-0000-4000-8000-000000000004', 'dave@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Dave"}'::jsonb, now(), now());

-- Grants ----------------------------------------------------------------------

SELECT ok(
    NOT has_function_privilege('anon', 'public.dissolve_group(uuid)', 'EXECUTE')
        AND has_function_privilege('authenticated', 'public.dissolve_group(uuid)', 'EXECUTE'),
    'dissolve_group is only for signed-in users'
);

SELECT ok(
    NOT has_function_privilege('anon', 'public.transfer_group_and_leave(uuid,uuid)', 'EXECUTE')
        AND has_function_privilege('authenticated', 'public.transfer_group_and_leave(uuid,uuid)', 'EXECUTE'),
    'transfer_group_and_leave is only for signed-in users'
);

-- Alice starts a group; Bob and Carol join with an invite token --------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT set_config('test.group_id', (SELECT id::text FROM public.create_group('Mobile team')), true);
SELECT set_config('test.token', (SELECT token FROM public.create_group_invite(current_setting('test.group_id')::uuid)), true);

SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);
SELECT lives_ok(
    $$ SELECT * FROM public.join_group_with_token(current_setting('test.token')) $$,
    'Bob joins with the invite token'
);

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);
SELECT lives_ok(
    $$ SELECT * FROM public.join_group_with_token(current_setting('test.token')) $$,
    'Carol joins with the invite token'
);

-- Only the owner can dissolve the group or hand it over --------------------------------

SELECT set_config('request.jwt.claim.sub', 'd4000000-0000-4000-8000-000000000004', true);

SELECT throws_ok(
    $$ SELECT public.dissolve_group(current_setting('test.group_id')::uuid) $$,
    '42501',
    'not_an_owner',
    'a non-member cannot dissolve a group'
);

SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT throws_ok(
    $$ SELECT public.dissolve_group(current_setting('test.group_id')::uuid) $$,
    '42501',
    'not_an_owner',
    'a member cannot dissolve the group'
);

SELECT throws_ok(
    $$ SELECT public.transfer_group_and_leave(current_setting('test.group_id')::uuid, 'c3000000-0000-4000-8000-000000000003'::uuid) $$,
    '42501',
    'not_an_owner',
    'a member cannot hand the group to someone else'
);

SELECT set_config('request.jwt.claim.sub', '', true);

SELECT throws_ok(
    $$ SELECT public.dissolve_group(current_setting('test.group_id')::uuid) $$,
    '28000',
    'not_authenticated',
    'dissolving a group needs a signed-in user'
);

SELECT throws_ok(
    $$ SELECT public.transfer_group_and_leave(current_setting('test.group_id')::uuid, 'b2000000-0000-4000-8000-000000000002'::uuid) $$,
    '28000',
    'not_authenticated',
    'handing a group over needs a signed-in user'
);

-- The new owner has to be another member ----------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT throws_ok(
    $$ SELECT public.transfer_group_and_leave(current_setting('test.group_id')::uuid, 'a1000000-0000-4000-8000-000000000001'::uuid) $$,
    '22023',
    'Choose another group member',
    'the owner cannot hand the group to themselves'
);

SELECT throws_ok(
    $$ SELECT public.transfer_group_and_leave(current_setting('test.group_id')::uuid, 'd4000000-0000-4000-8000-000000000004'::uuid) $$,
    '42501',
    'not_a_member',
    'the group cannot be handed to a non-member'
);

-- Members may leave; the owner may not leave the group ownerless --------------------------------

DELETE FROM public.memberships
WHERE group_id = current_setting('test.group_id')::uuid AND user_id = auth.uid();

SELECT is(
    (SELECT count(*) FROM public.memberships WHERE group_id = current_setting('test.group_id')::uuid AND user_id = auth.uid()),
    1::bigint,
    'the owner cannot leave by deleting the membership'
);

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);

DELETE FROM public.memberships
WHERE group_id = current_setting('test.group_id')::uuid AND user_id = auth.uid();

SELECT is(
    (SELECT count(*) FROM public.memberships WHERE group_id = current_setting('test.group_id')::uuid AND user_id = auth.uid()),
    0::bigint,
    'a member can leave the group'
);

-- Handing the group over ----------------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT lives_ok(
    $$ SELECT public.transfer_group_and_leave(current_setting('test.group_id')::uuid, 'b2000000-0000-4000-8000-000000000002'::uuid) $$,
    'the owner hands the group to Bob and leaves'
);

SELECT is(
    (SELECT count(*) FROM public.my_group_summaries() WHERE id = current_setting('test.group_id')::uuid),
    0::bigint,
    'the former owner no longer sees the group'
);

SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT is(
    (SELECT my_role FROM public.my_group_summaries() WHERE id = current_setting('test.group_id')::uuid),
    'owner',
    'Bob is now the owner'
);

SELECT is(
    (SELECT count(*) FROM public.memberships WHERE group_id = current_setting('test.group_id')::uuid),
    1::bigint,
    'only Bob is left in the group'
);

-- Dissolving removes everything that belonged to the group ------------------------------------

INSERT INTO public.messages (group_id, client_id, body)
VALUES (current_setting('test.group_id')::uuid, gen_random_uuid(), 'Last message');

INSERT INTO public.shared_files (group_id, file_name, mime_type, size_bytes, storage_path)
VALUES (
    current_setting('test.group_id')::uuid,
    'notes.pdf',
    'application/pdf',
    1024,
    current_setting('test.group_id') || '/b2000000-0000-4000-8000-000000000002/object-1'
);

SELECT set_config('test.old_token', (SELECT token FROM public.create_group_invite(current_setting('test.group_id')::uuid)), true);

SELECT lives_ok(
    $$ SELECT public.dissolve_group(current_setting('test.group_id')::uuid) $$,
    'the owner dissolves the group'
);

RESET ROLE;

SELECT is(
    (SELECT count(*) FROM public.groups WHERE id = current_setting('test.group_id')::uuid),
    0::bigint,
    'the group is gone'
);

SELECT is(
    (SELECT count(*) FROM public.memberships WHERE group_id = current_setting('test.group_id')::uuid),
    0::bigint,
    'its memberships are gone'
);

SELECT is(
    (SELECT count(*) FROM public.messages WHERE group_id = current_setting('test.group_id')::uuid),
    0::bigint,
    'its messages are gone'
);

SELECT is(
    (SELECT count(*) FROM public.invitations WHERE group_id = current_setting('test.group_id')::uuid),
    0::bigint,
    'its invitations are gone'
);

SELECT is(
    (SELECT count(*) FROM public.shared_files WHERE group_id = current_setting('test.group_id')::uuid),
    0::bigint,
    'its file records are gone'
);

-- An old token cannot bring a dissolved group back ----------------------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'd4000000-0000-4000-8000-000000000004', true);

SELECT throws_ok(
    $$ SELECT * FROM public.join_group_with_token(current_setting('test.old_token')) $$,
    '22023',
    'invite_not_found',
    'an invite token of a dissolved group no longer works'
);

RESET ROLE;

SELECT * FROM finish();
ROLLBACK;
