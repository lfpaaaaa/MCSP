BEGIN;

SELECT plan(13);

-- Fixtures, created as the database owner ------------------------------------

INSERT INTO auth.users (id, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
VALUES
    ('a1000000-0000-4000-8000-000000000001', 'alice@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Alice"}'::jsonb, now(), now()),
    ('b2000000-0000-4000-8000-000000000002', 'bob@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Bob"}'::jsonb, now(), now());

INSERT INTO public.groups (id, name, created_by)
VALUES ('e5000000-0000-4000-8000-000000000005', 'Mobile team', 'a1000000-0000-4000-8000-000000000001');

INSERT INTO public.memberships (group_id, user_id, role)
VALUES
    ('e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 'owner'),
    ('e5000000-0000-4000-8000-000000000005', 'b2000000-0000-4000-8000-000000000002', 'member');

-- Schema ----------------------------------------------------------------------

SELECT has_table('public', 'device_tokens', 'device_tokens table exists');

SELECT ok(
    (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.device_tokens'::regclass),
    'row level security is enabled on device_tokens'
);

SELECT ok(
    EXISTS (
        SELECT 1 FROM pg_trigger
        WHERE tgrelid = 'public.messages'::regclass AND tgname = 'messages_notify_new_message'
    ),
    'new messages fire the notification trigger'
);

-- Anonymous visitors -----------------------------------------------------------

SET LOCAL ROLE anon;

SELECT throws_ok(
    $$ SELECT public.register_device_token('anon-token-000000000001') $$,
    '42501',
    NULL,
    'anonymous visitors cannot register a device'
);

RESET ROLE;

-- Registering ------------------------------------------------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT lives_ok(
    $$ SELECT public.register_device_token('alice-phone-token-00000001') $$,
    'a signed-in user can register a device'
);

SELECT lives_ok(
    $$ SELECT public.register_device_token('alice-phone-token-00000001') $$,
    'registering the same device again is fine'
);

SELECT is(
    (SELECT count(*) FROM public.device_tokens),
    1::bigint,
    'a device is stored once'
);

SELECT throws_ok(
    $$ SELECT public.register_device_token('short') $$,
    '23514',
    NULL,
    'a token that is too short is rejected'
);

-- A device that changes hands -----------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT public.register_device_token('alice-phone-token-00000001');

SELECT is(
    (SELECT user_id FROM public.device_tokens WHERE token = 'alice-phone-token-00000001'),
    'b2000000-0000-4000-8000-000000000002'::uuid,
    'a device registered by another user now belongs to that user'
);

SELECT is(
    (SELECT count(*) FROM public.device_tokens),
    1::bigint,
    'other users'' tokens are not visible'
);

-- Removing ------------------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT public.unregister_device_token('alice-phone-token-00000001');

RESET ROLE;

SELECT is(
    (SELECT count(*) FROM public.device_tokens WHERE token = 'alice-phone-token-00000001'),
    1::bigint,
    'only the owner of a token can remove it'
);

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT public.unregister_device_token('alice-phone-token-00000001');

RESET ROLE;

SELECT is(
    (SELECT count(*) FROM public.device_tokens),
    0::bigint,
    'the owner can remove a token'
);

-- Messages are stored even when notifications are not configured --------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

SELECT lives_ok(
    $$ INSERT INTO public.messages (group_id, client_id, body)
       VALUES ('e5000000-0000-4000-8000-000000000005', gen_random_uuid(), 'Hello') $$,
    'a message is stored when the notification secrets are missing'
);

SELECT * FROM finish();
ROLLBACK;
