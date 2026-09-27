BEGIN;

SELECT plan(36);

-- Fixtures, created as the database owner ------------------------------------

INSERT INTO auth.users (id, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at)
VALUES
    ('a1000000-0000-4000-8000-000000000001', 'alice@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Alice"}'::jsonb, now(), now()),
    ('b2000000-0000-4000-8000-000000000002', 'bob@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Bob"}'::jsonb, now(), now()),
    ('c3000000-0000-4000-8000-000000000003', 'carol@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Carol"}'::jsonb, now(), now()),
    ('d4000000-0000-4000-8000-000000000004', 'dave@example.com', '{"provider":"email","providers":["email"]}'::jsonb, '{"display_name":"Dave"}'::jsonb, now(), now());

INSERT INTO public.groups (id, name, course_code, created_by)
VALUES ('e5000000-0000-4000-8000-000000000005', 'Mobile team', 'COMP90018', 'a1000000-0000-4000-8000-000000000001');

INSERT INTO public.memberships (group_id, user_id, role)
VALUES ('e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', 'owner');

INSERT INTO public.invitations (group_id, token_hash, created_by, expires_at, max_uses)
VALUES
    ('e5000000-0000-4000-8000-000000000005', encode(sha256(convert_to('valid-token', 'UTF8')), 'hex'), 'a1000000-0000-4000-8000-000000000001', now() + interval '10 minutes', 20),
    ('e5000000-0000-4000-8000-000000000005', encode(sha256(convert_to('expired-token', 'UTF8')), 'hex'), 'a1000000-0000-4000-8000-000000000001', now() - interval '1 minute', 20),
    ('e5000000-0000-4000-8000-000000000005', encode(sha256(convert_to('single-use-token', 'UTF8')), 'hex'), 'a1000000-0000-4000-8000-000000000001', now() + interval '10 minutes', 1);

-- Schema ----------------------------------------------------------------------

SELECT has_table('public', 'groups', 'groups table exists');
SELECT has_table('public', 'memberships', 'memberships table exists');
SELECT has_table('public', 'invitations', 'invitations table exists');
SELECT has_table('public', 'messages', 'messages table exists');
SELECT has_table('public', 'shared_files', 'shared_files table exists');

SELECT ok(
    (
        SELECT bool_and(relrowsecurity)
        FROM pg_class
        WHERE oid IN (
            'public.groups'::regclass,
            'public.memberships'::regclass,
            'public.invitations'::regclass,
            'public.messages'::regclass,
            'public.shared_files'::regclass
        )
    ),
    'row level security is enabled on every collaboration table'
);

SELECT ok(
    EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'messages'
    ),
    'new messages are published to realtime subscribers'
);

-- Anonymous visitors -----------------------------------------------------------

SET LOCAL ROLE anon;

SELECT throws_ok(
    'SELECT * FROM public.groups',
    '42501',
    NULL,
    'anonymous users cannot read groups'
);

RESET ROLE;

-- Bob before joining -----------------------------------------------------------

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT is(
    (SELECT count(*) FROM public.groups),
    0::bigint,
    'non-members cannot see the group'
);

SELECT throws_ok(
    $$INSERT INTO public.messages (group_id, client_id, body) VALUES ('e5000000-0000-4000-8000-000000000005', gen_random_uuid(), 'Hi')$$,
    '42501',
    NULL,
    'non-members cannot post messages'
);

SELECT throws_ok(
    $$SELECT * FROM public.group_members('e5000000-0000-4000-8000-000000000005')$$,
    '42501',
    'not_a_member',
    'non-members cannot list members'
);

SELECT throws_ok(
    $$SELECT * FROM public.create_group_invite('e5000000-0000-4000-8000-000000000005')$$,
    '42501',
    'not_a_member',
    'non-members cannot create invites'
);

SELECT throws_ok(
    $$SELECT public.join_group_with_token('no-such-token')$$,
    '22023',
    'invite_not_found',
    'unknown tokens are rejected'
);

SELECT throws_ok(
    $$SELECT public.join_group_with_token('expired-token')$$,
    '22023',
    'invite_expired',
    'expired tokens are rejected'
);

-- Bob joins --------------------------------------------------------------------

SELECT is(
    (SELECT name FROM public.join_group_with_token('valid-token')),
    'Mobile team',
    'a valid token joins the group'
);

SELECT is(
    (SELECT count(*) FROM public.groups),
    1::bigint,
    'members can see the group'
);

SELECT lives_ok(
    $$SELECT public.join_group_with_token('valid-token')$$,
    'joining a group again succeeds'
);

SELECT lives_ok(
    $$INSERT INTO public.messages (group_id, client_id, body) VALUES ('e5000000-0000-4000-8000-000000000005', gen_random_uuid(), 'Hello team')$$,
    'members can post messages'
);

SELECT throws_ok(
    $$INSERT INTO public.messages (group_id, client_id, body, sender_id) VALUES ('e5000000-0000-4000-8000-000000000005', gen_random_uuid(), 'Spoofed', 'a1000000-0000-4000-8000-000000000001')$$,
    '42501',
    NULL,
    'members cannot post as someone else'
);

SELECT is(
    (SELECT count(*) FROM public.group_members('e5000000-0000-4000-8000-000000000005')),
    2::bigint,
    'members can list the group members'
);

SELECT is(
    (SELECT display_name FROM public.group_members('e5000000-0000-4000-8000-000000000005') WHERE role = 'owner'),
    'Alice',
    'the member list shows display names'
);

-- Invite usage limits ----------------------------------------------------------

SELECT set_config('request.jwt.claim.sub', 'c3000000-0000-4000-8000-000000000003', true);

SELECT lives_ok(
    $$SELECT public.join_group_with_token('single-use-token')$$,
    'a single-use token works once'
);

SELECT set_config('request.jwt.claim.sub', 'd4000000-0000-4000-8000-000000000004', true);

SELECT throws_ok(
    $$SELECT public.join_group_with_token('single-use-token')$$,
    '22023',
    'invite_used_up',
    'a used-up token is rejected'
);

-- Unread counts ----------------------------------------------------------------

RESET ROLE;

UPDATE public.memberships
SET last_read_at = now() - interval '1 hour'
WHERE group_id = 'e5000000-0000-4000-8000-000000000005'
  AND user_id = 'b2000000-0000-4000-8000-000000000002';

INSERT INTO public.messages (group_id, sender_id, client_id, body)
VALUES ('e5000000-0000-4000-8000-000000000005', 'a1000000-0000-4000-8000-000000000001', gen_random_uuid(), 'Meeting at 3 pm');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'b2000000-0000-4000-8000-000000000002', true);

SELECT is(
    (SELECT unread_count FROM public.my_group_summaries() WHERE id = 'e5000000-0000-4000-8000-000000000005'),
    1,
    'messages from other members count as unread'
);

SELECT lives_ok(
    $$SELECT public.mark_group_read('e5000000-0000-4000-8000-000000000005')$$,
    'members can mark a group as read'
);

SELECT is(
    (SELECT unread_count FROM public.my_group_summaries() WHERE id = 'e5000000-0000-4000-8000-000000000005'),
    0,
    'marking a group as read clears the unread count'
);

-- Creating groups --------------------------------------------------------------

SELECT is(
    (SELECT course_code FROM public.create_group('Revision', 'swen90006')),
    'SWEN90006',
    'course codes are stored in upper case'
);

SELECT is(
    (SELECT my_role FROM public.my_group_summaries() WHERE name = 'Revision'),
    'owner',
    'the creator becomes the group owner'
);

SELECT throws_ok(
    $$SELECT public.create_group('Bad code', 'COMP9')$$,
    '23514',
    NULL,
    'invalid course codes are rejected'
);

-- Invite tokens are stored as hashes -------------------------------------------

RESET ROLE;
SELECT set_config('request.jwt.claim.sub', 'a1000000-0000-4000-8000-000000000001', true);

CREATE TEMP TABLE new_invite AS
SELECT * FROM public.create_group_invite('e5000000-0000-4000-8000-000000000005');

SELECT is(
    (SELECT length(token) FROM new_invite),
    64,
    'new tokens have 64 hexadecimal characters'
);

SELECT ok(
    EXISTS (
        SELECT 1
        FROM public.invitations AS i
        JOIN new_invite AS n ON i.token_hash = encode(sha256(convert_to(n.token, 'UTF8')), 'hex')
    ),
    'the database stores the SHA-256 hash of a new token'
);

SELECT ok(
    NOT EXISTS (
        SELECT 1
        FROM public.invitations AS i
        JOIN new_invite AS n ON i.token_hash = n.token
    ),
    'plain tokens are never stored'
);

SELECT is(
    (
        SELECT use_count
        FROM public.invitations
        WHERE token_hash = encode(sha256(convert_to('valid-token', 'UTF8')), 'hex')
    ),
    1,
    'joining twice uses an invite only once'
);

-- Function privileges ----------------------------------------------------------

SELECT ok(
    NOT has_function_privilege('authenticated', 'public.sync_auth_user_profile()', 'EXECUTE'),
    'signed-in users cannot call the profile trigger function directly'
);

SELECT ok(
    NOT has_function_privilege('anon', 'public.join_group_with_token(text)', 'EXECUTE'),
    'anonymous users cannot join groups'
);

SELECT ok(
    NOT has_function_privilege('anon', 'private.is_group_member(uuid)', 'EXECUTE'),
    'anonymous users cannot call private helpers'
);

SELECT * FROM finish();
ROLLBACK;
