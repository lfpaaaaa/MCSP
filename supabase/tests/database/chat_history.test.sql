BEGIN;

SELECT plan(14);

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

-- Five messages. The last two share a timestamp, so their ids decide the order.
INSERT INTO public.messages (id, group_id, sender_id, client_id, body, created_at)
VALUES
    ('f1000000-0000-4000-8000-000000000001', 'e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', gen_random_uuid(), 'one', '2026-09-25 01:00:00+00'),
    ('f2000000-0000-4000-8000-000000000002', 'e5000000-0000-4000-8000-000000000005', 'b2000000-0000-4000-8000-000000000002', gen_random_uuid(), 'two', '2026-09-25 02:00:00+00'),
    ('f3000000-0000-4000-8000-000000000003', 'e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', gen_random_uuid(), 'three', '2026-09-25 03:00:00+00'),
    ('f4000000-0000-4000-8000-000000000004', 'e5000000-0000-4000-8000-000000000005', 'b2000000-0000-4000-8000-000000000002', gen_random_uuid(), 'four', '2026-09-25 04:00:00+00'),
    ('f5000000-0000-4000-8000-000000000005', 'e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', gen_random_uuid(), 'five', '2026-09-25 04:00:00+00');

-- Members ----------------------------------------------------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT is(
    ARRAY(SELECT body FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005', NULL, NULL, 2)),
    ARRAY['five', 'four'],
    'the first page holds the newest messages, newest first'
);

SELECT is(
    ARRAY(SELECT body FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005', '2026-09-25 04:00:00+00', 'f4000000-0000-4000-8000-000000000004', 2)),
    ARRAY['three', 'two'],
    'the next page continues after the cursor, even when timestamps are equal'
);

SELECT is(
    ARRAY(SELECT body FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005', '2026-09-25 02:00:00+00', 'f2000000-0000-4000-8000-000000000002', 30)),
    ARRAY['one'],
    'the last page is shorter than the page size'
);

SELECT is(
    ARRAY(SELECT body FROM public.group_messages_after('e5000000-0000-4000-8000-000000000005', '2026-09-25 02:00:00+00', 'f2000000-0000-4000-8000-000000000002', 10)),
    ARRAY['three', 'four', 'five'],
    'catching up returns the newer messages, oldest first'
);

SELECT is(
    (SELECT sender_name FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005', NULL, NULL, 1)),
    'Alice',
    'pages include the sender''s display name'
);

SELECT is(
    (SELECT count(*) FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005')),
    5::bigint,
    'without a cursor the latest page is returned'
);

SELECT throws_ok(
    $$SELECT * FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005', NULL, NULL, 0)$$,
    '22023',
    'invalid_page_size',
    'page sizes must be between 1 and 100'
);

SELECT throws_ok(
    $$SELECT * FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005', '2026-09-25 04:00:00+00', NULL, 2)$$,
    '22023',
    'invalid_cursor',
    'a cursor needs both a timestamp and an id'
);

SELECT throws_ok(
    $$SELECT * FROM public.group_messages_after('e5000000-0000-4000-8000-000000000005', NULL, NULL, 10)$$,
    '22023',
    'invalid_cursor',
    'catching up needs a cursor'
);

-- Non-members ------------------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);

SELECT throws_ok(
    $$SELECT * FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005')$$,
    '42501',
    'not_a_member',
    'non-members cannot read the history'
);

SELECT throws_ok(
    $$SELECT * FROM public.group_messages_after('e5000000-0000-4000-8000-000000000005', '2026-09-25 02:00:00+00', 'f2000000-0000-4000-8000-000000000002', 10)$$,
    '42501',
    'not_a_member',
    'non-members cannot catch up on the history'
);

RESET ROLE;

-- Function privileges ----------------------------------------------------------

SELECT ok(
    NOT has_function_privilege('anon', 'public.group_messages_before(uuid,timestamptz,uuid,integer)', 'EXECUTE'),
    'anonymous users cannot read chat history'
);

SELECT ok(
    has_function_privilege('authenticated', 'public.group_messages_after(uuid,timestamptz,uuid,integer)', 'EXECUTE'),
    'signed-in users can call the history functions'
);

SELECT ok(
    NOT has_function_privilege('anon', 'private.group_messages_before(uuid,timestamptz,uuid,integer)', 'EXECUTE'),
    'anonymous users cannot call the private history functions'
);

SELECT * FROM finish();
ROLLBACK;
