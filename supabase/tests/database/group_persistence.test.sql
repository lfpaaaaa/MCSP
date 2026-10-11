BEGIN;
SELECT plan(13);

-- Isolated accounts; every row made by this test is rolled back.
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES ('a1100000-0000-4000-8000-000000000011', 'group-persistence-owner@example.com', '{"display_name":"Owner"}'),
       ('b1100000-0000-4000-8000-000000000011', 'group-persistence-other@example.com', '{"display_name":"Other"}');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub', 'a1100000-0000-4000-8000-000000000011', true);
SELECT lives_ok($$SELECT public.create_group('Persistent study group', NULL)$$, 'create_group persists a manual group');
SELECT is((SELECT count(*) FROM public.my_group_summaries()), 1::bigint, 'the owner sees the created group');
SELECT is((SELECT my_role FROM public.my_group_summaries()), 'owner', 'owner membership is saved');
SELECT ok((SELECT timetable_key IS NULL FROM public.my_group_summaries()), 'manual groups are separate from timetable groups');

-- No session / another account cannot see the owner's list.
SELECT set_config('request.jwt.claim.sub', '', true);
SELECT is((SELECT count(*) FROM public.my_group_summaries()), 0::bigint, 'signed-out requests cannot read groups');
SELECT set_config('request.jwt.claim.sub', 'b1100000-0000-4000-8000-000000000011', true);
SELECT is((SELECT count(*) FROM public.my_group_summaries()), 0::bigint, 'another account cannot read the group');
SELECT set_config('request.jwt.claim.sub', 'a1100000-0000-4000-8000-000000000011', true);
SELECT is((SELECT name FROM public.my_group_summaries()), 'Persistent study group', 'returning to the same account restores the group');
SELECT is((SELECT count(*) FROM public.memberships WHERE user_id = auth.uid()), 1::bigint, 'session changes keep the membership');

-- Re-importing the timetable and expiry of the short join code do not remove manual groups.
SELECT lives_ok($$SELECT public.sync_timetable_groups('[{"course_code":"COMP90018","name":"Mobile computing","activity":"course"}]')$$, 'timetable import succeeds');
SELECT is((SELECT count(*) FROM public.my_group_summaries() WHERE timetable_key IS NULL), 1::bigint, 'timetable import preserves manual groups');
RESET ROLE;
UPDATE public.groups SET created_at = now() - interval '1 day'
WHERE created_by = 'a1100000-0000-4000-8000-000000000011';
SET LOCAL ROLE authenticated;
SELECT is((SELECT count(*) FROM public.my_group_summaries() WHERE timetable_key IS NULL), 1::bigint, 'join code expiry does not expire the group');
SELECT lives_ok($$SELECT public.dissolve_group((SELECT id FROM public.my_group_summaries() WHERE timetable_key IS NULL))$$, 'an explicit dissolve still removes the manual group');
SELECT is((SELECT count(*) FROM public.my_group_summaries() WHERE timetable_key IS NULL), 0::bigint, 'dissolved group no longer appears on later reads');
SELECT * FROM finish();
ROLLBACK;
