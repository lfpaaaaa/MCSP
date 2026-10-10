BEGIN;
SELECT plan(5);
INSERT INTO auth.users(id,email) VALUES
('a1000000-0000-4000-8000-000000000001','chat-save-a@example.com'),
('b2000000-0000-4000-8000-000000000002','chat-save-b@example.com');
SELECT set_config('test.spec','[{"course_code":"COMP90018","name":"COMP90018-tutorial","activity":"tutorial","day":4,"start":"15:00","end":"16:00","location":"PAR-160"}]',true);
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a1000000-0000-4000-8000-000000000001',true);
SELECT set_config('test.group',(SELECT id::text FROM public.sync_timetable_groups(current_setting('test.spec')::jsonb)),true);
SELECT lives_ok($$INSERT INTO public.messages(group_id,client_id,body) VALUES(current_setting('test.group')::uuid,'e1000000-0000-4000-8000-000000000001','Persisted chat test')$$,'timetable group member can persist a message');
SELECT is((SELECT body FROM public.group_messages_before(current_setting('test.group')::uuid,NULL,NULL,30)),'Persisted chat test','history returns stored text');
SELECT is((SELECT sender_id FROM public.messages WHERE group_id=current_setting('test.group')::uuid),'a1000000-0000-4000-8000-000000000001'::uuid,'server records the signed-in sender');
SELECT set_config('request.jwt.claim.sub','b2000000-0000-4000-8000-000000000002',true);
SELECT is((SELECT count(*) FROM public.messages WHERE group_id=current_setting('test.group')::uuid),0::bigint,'non-member cannot see text');
SELECT public.sync_timetable_groups(current_setting('test.spec')::jsonb);
SELECT is((SELECT body FROM public.group_messages_before(current_setting('test.group')::uuid,NULL,NULL,30)),'Persisted chat test','another group member reads the same stored message');
SELECT * FROM finish();
ROLLBACK;
