BEGIN;
SELECT plan(21);
INSERT INTO auth.users(id,email,raw_app_meta_data,raw_user_meta_data,created_at,updated_at) VALUES
('a7130000-0000-4000-8000-000000000001','speech-owner@example.test','{"provider":"email"}','{"display_name":"Owner"}',now(),now()),
('a7130000-0000-4000-8000-000000000002','speech-member@example.test','{"provider":"email"}','{"display_name":"Member"}',now(),now()),
('a7130000-0000-4000-8000-000000000003','speech-outsider@example.test','{"provider":"email"}','{"display_name":"Outsider"}',now(),now());
INSERT INTO public.groups(id,name,created_by) VALUES('a7130000-0000-4000-8000-000000000004','Speech fixture','a7130000-0000-4000-8000-000000000001');
INSERT INTO public.memberships(group_id,user_id,role) VALUES
('a7130000-0000-4000-8000-000000000004','a7130000-0000-4000-8000-000000000001','owner'),
('a7130000-0000-4000-8000-000000000004','a7130000-0000-4000-8000-000000000002','member');
INSERT INTO public.shared_files(id,group_id,uploader_id,file_name,mime_type,size_bytes,storage_path,allow_downloads) VALUES
('a7130000-0000-4000-8000-000000000005','a7130000-0000-4000-8000-000000000004','a7130000-0000-4000-8000-000000000001','audio.m4a','audio/mp4',20,'a7130000-0000-4000-8000-000000000004/a7130000-0000-4000-8000-000000000001/audio',false),
('a7130000-0000-4000-8000-000000000006','a7130000-0000-4000-8000-000000000004','a7130000-0000-4000-8000-000000000001','text.txt','text/plain',20,'a7130000-0000-4000-8000-000000000004/a7130000-0000-4000-8000-000000000001/text',true);
SELECT ok(NOT has_function_privilege('anon','public.file_transcript(uuid)','EXECUTE'),'anonymous cannot read transcripts');
SELECT ok(NOT has_function_privilege('authenticated','public.claim_file_transcript()','EXECUTE'),'clients cannot claim jobs');
SELECT ok(NOT has_function_privilege('authenticated','public.finish_file_transcript(uuid,uuid,text,text,text)','EXECUTE'),'clients cannot forge results');
SELECT ok(NOT has_table_privilege('authenticated','public.file_transcripts','SELECT'),'direct transcript reads denied');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7130000-0000-4000-8000-000000000001',true);
SELECT is((SELECT count(*) FROM public.file_transcript('a7130000-0000-4000-8000-000000000005')),0::bigint,'no transcript before request');
SELECT throws_ok($$SELECT public.request_file_transcript('a7130000-0000-4000-8000-000000000006')$$,'22023','audio_required','non-audio rejected');
SELECT is((SELECT status FROM public.request_file_transcript('a7130000-0000-4000-8000-000000000005')),'queued','uploader queues audio');
SELECT is((SELECT count(*) FROM public.request_file_transcript('a7130000-0000-4000-8000-000000000005')),1::bigint,'duplicate request remains one job');
SELECT set_config('request.jwt.claim.sub','a7130000-0000-4000-8000-000000000002',true);
SELECT is((SELECT status FROM public.file_transcript('a7130000-0000-4000-8000-000000000005')),'queued','member reads status even when downloads disabled');
SELECT throws_ok($$SELECT public.request_file_transcript('a7130000-0000-4000-8000-000000000005')$$,'42501','not_file_uploader','member cannot spend uploader transcription work');
SELECT set_config('request.jwt.claim.sub','a7130000-0000-4000-8000-000000000003',true);
SELECT throws_ok($$SELECT public.file_transcript('a7130000-0000-4000-8000-000000000005')$$,'42501','not_a_member','outsider cannot read');
RESET ROLE;
-- Scope worker claims to this transaction fixture even on a populated local backend.
UPDATE public.file_transcripts SET status='ready' WHERE file_id <> 'a7130000-0000-4000-8000-000000000005';
SET LOCAL ROLE service_role;
CREATE TEMP TABLE claimed AS SELECT * FROM public.claim_file_transcript();
SELECT is((SELECT count(*) FROM claimed),1::bigint,'worker claims a job');
SELECT is((SELECT count(*) FROM public.claim_file_transcript()),0::bigint,'lease prevents duplicate processing');
SELECT is(public.finish_file_transcript('a7130000-0000-4000-8000-000000000005',gen_random_uuid(),'forged','en',NULL),false,'wrong lease cannot finish');
SELECT is(public.finish_file_transcript('a7130000-0000-4000-8000-000000000005',(SELECT lease_id FROM claimed),NULL,NULL,'transcription_failed'),true,'worker saves failure');
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7130000-0000-4000-8000-000000000001',true);
SELECT is((SELECT status FROM public.request_file_transcript('a7130000-0000-4000-8000-000000000005')),'queued','uploader can retry failed transcription');
RESET ROLE;
SET LOCAL ROLE service_role;
CREATE TEMP TABLE retried AS SELECT * FROM public.claim_file_transcript();
SELECT is(public.finish_file_transcript('a7130000-0000-4000-8000-000000000005',(SELECT lease_id FROM claimed),'old','en',NULL),false,'old lease cannot overwrite retried job');
SELECT is(public.finish_file_transcript('a7130000-0000-4000-8000-000000000005',(SELECT lease_id FROM retried),'Welcome to class.','en',NULL),true,'worker stores transcript');
RESET ROLE;
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7130000-0000-4000-8000-000000000002',true);
SELECT is((SELECT transcript FROM public.file_transcript('a7130000-0000-4000-8000-000000000005')),'Welcome to class.','group member can view saved text');
SELECT set_config('request.jwt.claim.sub','a7130000-0000-4000-8000-000000000001',true);
SELECT is((SELECT status FROM public.request_file_transcript('a7130000-0000-4000-8000-000000000005')),'ready','completed result is not queued again');
DELETE FROM public.shared_files WHERE id='a7130000-0000-4000-8000-000000000005';
RESET ROLE;
SELECT is((SELECT count(*) FROM public.file_transcripts WHERE file_id='a7130000-0000-4000-8000-000000000005'),0::bigint,'file deletion removes transcript too');
SELECT * FROM finish();
ROLLBACK;
