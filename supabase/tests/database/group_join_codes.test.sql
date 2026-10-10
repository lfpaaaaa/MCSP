BEGIN;

SELECT plan(15);

-- Fixtures, created as the database owner ------------------------------------

INSERT INTO auth.users (id, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
VALUES
    ('a1000000-0000-4000-8000-000000000001', 'alice@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Alice"}'::jsonb, now(), now()),
    ('b2000000-0000-4000-8000-000000000002', 'bob@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Bob"}'::jsonb, now(), now()),
    ('c3000000-0000-4000-8000-000000000003', 'carol@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Carol"}'::jsonb, now(), now()),
    ('d4000000-0000-4000-8000-000000000004', 'dave@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Dave"}'::jsonb, now(), now());

-- Schema ----------------------------------------------------------------------

SELECT has_column('public', 'groups', 'join_code', 'groups have a join code');

-- Creating a group gives it a code --------------------------------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT set_config('test.group_id', (SELECT id::text FROM public.create_group('Mobile team')), true);
SELECT set_config('test.code', (SELECT join_code FROM public.groups WHERE id = current_setting('test.group_id')::uuid), true);

SELECT matches(
    current_setting('test.code'),
    '^[A-HJ-NP-Z2-9]{6}$',
    'a new group gets a six-character code without confusable characters'
);

-- Joining with the code --------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT is(
    (SELECT id FROM public.join_group_with_token(' ' || lower(current_setting('test.code')) || ' ')),
    current_setting('test.group_id')::uuid,
    'a code joins its group whatever the case and spacing'
);

SELECT is(
    (SELECT count(*) FROM public.memberships WHERE group_id = current_setting('test.group_id')::uuid),
    2::bigint,
    'the joiner is now a member'
);

SELECT is(
    (SELECT id FROM public.join_group_with_token(current_setting('test.code'))),
    current_setting('test.group_id')::uuid,
    'a member can use the code again'
);

SELECT is(
    (SELECT count(*) FROM public.memberships WHERE group_id = current_setting('test.group_id')::uuid),
    2::bigint,
    'joining again does not add a second membership'
);

SELECT is(
    (SELECT join_code FROM public.groups WHERE id = current_setting('test.group_id')::uuid),
    current_setting('test.code'),
    'members can see the code'
);

SELECT is(
    (SELECT join_code FROM public.my_group_summaries() WHERE id = current_setting('test.group_id')::uuid),
    current_setting('test.code'),
    'the group list carries the code'
);

-- Wrong codes ------------------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);
SELECT set_config('test.started_at', clock_timestamp()::text, true);

SELECT throws_ok(
    $$ SELECT * FROM public.join_group_with_token('ZZZZZZ') $$,
    '22023',
    'invite_not_found',
    'an unknown code is rejected'
);

SELECT ok(
    clock_timestamp() - current_setting('test.started_at')::timestamptz >= interval '1 second',
    'a wrong code costs a one-second wait'
);

SELECT is(
    (SELECT count(*) FROM public.groups),
    0::bigint,
    'non-members cannot see any code'
);

-- Resetting the code -----------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT throws_ok(
    $$ SELECT public.reset_group_code(current_setting('test.group_id')::uuid) $$,
    '42501',
    'not_an_owner',
    'only an owner can reset the code'
);

SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);
SELECT set_config('test.new_code', public.reset_group_code(current_setting('test.group_id')::uuid), true);

SELECT ok(
    current_setting('test.new_code') ~ '^[A-HJ-NP-Z2-9]{6}$'
        AND current_setting('test.new_code') <> current_setting('test.code'),
    'an owner gets a different code'
);

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);

SELECT is(
    (SELECT id FROM public.join_group_with_token(current_setting('test.new_code'))),
    current_setting('test.group_id')::uuid,
    'the new code works'
);

-- Invite tokens still work -----------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);
SELECT set_config('test.token', (SELECT token FROM public.create_group_invite(current_setting('test.group_id')::uuid)), true);

SELECT set_config('request.jwt.claim.sub', 'd4000000-0000-4000-8000-000000000004', true);

SELECT is(
    (SELECT id FROM public.join_group_with_token(current_setting('test.token'))),
    current_setting('test.group_id')::uuid,
    'invite tokens from QR codes and NFC tags still join the group'
);

RESET ROLE;

SELECT * FROM finish();
ROLLBACK;
