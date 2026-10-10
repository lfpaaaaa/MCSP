BEGIN;
SELECT no_plan();

INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
 ('a1000000-0000-4000-8000-000000000001','nickname-alice@example.com','{"display_name":"Alice"}'),
 ('b2000000-0000-4000-8000-000000000002','nickname-bob@example.com','{"display_name":"Bob"}'),
 ('c3000000-0000-4000-8000-000000000003','nickname-carol@example.com','{"display_name":"Carol"}');
INSERT INTO public.groups (id, name, created_by) VALUES
 ('e5000000-0000-4000-8000-000000000005','Tutorial','b2000000-0000-4000-8000-000000000002'),
 ('e6000000-0000-4000-8000-000000000006','Workshop','a1000000-0000-4000-8000-000000000001');
INSERT INTO public.memberships (group_id,user_id,role) VALUES
 ('e5000000-0000-4000-8000-000000000005','a1000000-0000-4000-8000-000000000001','member'),
 ('e5000000-0000-4000-8000-000000000005','b2000000-0000-4000-8000-000000000002','owner'),
 ('e6000000-0000-4000-8000-000000000006','a1000000-0000-4000-8000-000000000001','owner');
INSERT INTO public.messages (id,group_id,sender_id,client_id,body,created_at) VALUES
 ('f1000000-0000-4000-8000-000000000001','e5000000-0000-4000-8000-000000000005','a1000000-0000-4000-8000-000000000001',gen_random_uuid(),'Before nickname change','2026-10-10T10:00:00Z');
INSERT INTO public.shared_files (group_id,uploader_id,file_name,mime_type,size_bytes,storage_path) VALUES
 ('e5000000-0000-4000-8000-000000000005','a1000000-0000-4000-8000-000000000001','photo.jpg','image/jpeg',10,'e5000000-0000-4000-8000-000000000005/a1000000-0000-4000-8000-000000000001/photo.jpg');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a1000000-0000-4000-8000-000000000001',true);
SELECT is(public.set_group_nickname('e5000000-0000-4000-8000-000000000005','  Alice tutorial  '),'Alice tutorial','ordinary members can save their own trimmed nickname');
SELECT is((SELECT display_name FROM public.profiles WHERE id=auth.uid()),'Alice','account profile is unchanged');
SELECT is((SELECT role FROM public.memberships WHERE group_id='e5000000-0000-4000-8000-000000000005' AND user_id=auth.uid()),'member','nickname update does not change role');
SELECT is((SELECT display_name FROM public.group_members('e6000000-0000-4000-8000-000000000006')),'Alice','another group still uses the account name');
SELECT throws_ok($$SELECT public.set_group_nickname('e5000000-0000-4000-8000-000000000005','   ')$$,'22023','invalid_nickname','blank names rejected');
SELECT throws_ok($$SELECT public.set_group_nickname('e5000000-0000-4000-8000-000000000005',NULL)$$,'22023','invalid_nickname','null names rejected');
SELECT throws_ok($$SELECT public.set_group_nickname('e5000000-0000-4000-8000-000000000005',repeat('x',41))$$,'22023','invalid_nickname','overlong names rejected');
SELECT throws_ok($$UPDATE public.memberships SET nickname='Impersonated' WHERE user_id='b2000000-0000-4000-8000-000000000002'$$,'42501',NULL,'direct writes cannot change another member nickname');

SELECT set_config('request.jwt.claim.sub','b2000000-0000-4000-8000-000000000002',true);
SELECT is((SELECT display_name FROM public.group_members('e5000000-0000-4000-8000-000000000005') WHERE user_id='a1000000-0000-4000-8000-000000000001'),'Alice tutorial','another member sees nickname in member list');
SELECT is((SELECT sender_name FROM public.group_messages_before('e5000000-0000-4000-8000-000000000005')),'Alice tutorial','existing history uses current nickname');
SELECT is((SELECT sender_name FROM public.group_messages_after('e5000000-0000-4000-8000-000000000005','2026-10-09','00000000-0000-0000-0000-000000000000',10)),'Alice tutorial','catch-up uses group nickname');
SELECT is((SELECT uploader_name FROM public.group_files('e5000000-0000-4000-8000-000000000005')),'Alice tutorial','attachment author uses nickname');
SELECT is(public.set_group_nickname('e5000000-0000-4000-8000-000000000005','组长 Bob'),'组长 Bob','Unicode nickname supported for owner');
SELECT is((SELECT display_name FROM public.group_members('e5000000-0000-4000-8000-000000000005') WHERE user_id='a1000000-0000-4000-8000-000000000001'),'Alice tutorial','owner changing own nickname leaves other members untouched');

SELECT set_config('request.jwt.claim.sub','c3000000-0000-4000-8000-000000000003',true);
SELECT throws_ok($$SELECT public.set_group_nickname('e5000000-0000-4000-8000-000000000005','Carol')$$,'42501','not_a_member','non-member cannot change group nickname');
SELECT throws_ok($$SELECT * FROM public.group_members('e5000000-0000-4000-8000-000000000005')$$,'42501','not_a_member','non-member cannot read group names');
SET LOCAL ROLE anon;
SELECT throws_ok($$SELECT public.set_group_nickname('e5000000-0000-4000-8000-000000000005','Anonymous')$$,'42501',NULL,'anonymous callers cannot set nickname');
RESET ROLE;
SELECT ok(EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname='supabase_realtime' AND tablename='memberships'),'nickname updates are in the realtime publication');
SELECT * FROM finish();
ROLLBACK;
