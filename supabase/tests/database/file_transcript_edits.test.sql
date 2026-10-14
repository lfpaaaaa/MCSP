BEGIN;
SELECT no_plan();
INSERT INTO auth.users(id,email,raw_app_meta_data,raw_user_meta_data,created_at,updated_at) VALUES
('a7140000-0000-4000-8000-000000000001','edit-owner@example.test','{"provider":"email"}','{"display_name":"Owner"}',now(),now()),
('a7140000-0000-4000-8000-000000000002','edit-member@example.test','{"provider":"email"}','{"display_name":"Member"}',now(),now()),
('a7140000-0000-4000-8000-000000000003','edit-outsider@example.test','{"provider":"email"}','{"display_name":"Outsider"}',now(),now());
INSERT INTO public.groups(id,name,created_by) VALUES('a7140000-0000-4000-8000-000000000004','Transcript edit fixture','a7140000-0000-4000-8000-000000000001');
INSERT INTO public.memberships(group_id,user_id,role) VALUES
('a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000001','owner'),
('a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000002','member');
INSERT INTO public.shared_files(id,group_id,uploader_id,file_name,mime_type,size_bytes,storage_path,allow_downloads) VALUES
('a7140000-0000-4000-8000-000000000005','a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000001','audio.m4a','audio/mp4',20,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000001/audio',false),
('a7140000-0000-4000-8000-000000000006','a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000001','queued.m4a','audio/mp4',20,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000001/queued',true);
INSERT INTO public.file_transcripts(file_id,status,transcript,language) VALUES
('a7140000-0000-4000-8000-000000000005','ready','Original transcript.','en'),
('a7140000-0000-4000-8000-000000000006','queued',NULL,NULL);
SELECT ok(NOT has_function_privilege('anon','public.update_file_transcript(uuid,text,integer)','EXECUTE'),'anonymous cannot edit');
SELECT ok(NOT has_table_privilege('authenticated','public.file_transcripts','UPDATE'),'clients cannot bypass edit RPC');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000002',true);
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005','member edit',0)$$,'42501','not_file_uploader','member cannot edit another recording');
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000003',true);
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005','outsider edit',0)$$,'42501','not_file_uploader','outsider cannot edit');
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000001',true);
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000006','unfinished',0)$$,'22023','transcript_not_ready','unfinished jobs cannot be edited');
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005',NULL,0)$$,'22023','invalid_transcript_edit','null text rejected');
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005',repeat('x',200001),0)$$,'22023','invalid_transcript_edit','oversized text rejected');
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005','text',NULL)$$,'22023','invalid_transcript_edit','expected revision required');
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005','text',-1)$$,'22023','invalid_transcript_edit','negative revision rejected');
SELECT is((SELECT transcript FROM public.update_file_transcript('a7140000-0000-4000-8000-000000000005','Corrected course transcript. 😀',0)),'Corrected course transcript. 😀','uploader saves Unicode corrections');
SELECT is((SELECT revision FROM public.file_transcript('a7140000-0000-4000-8000-000000000005')),1,'revision advances');
SELECT ok((SELECT edited_at IS NOT NULL FROM public.file_transcript('a7140000-0000-4000-8000-000000000005')),'manual edit is marked');
SELECT is((SELECT status FROM public.file_transcript('a7140000-0000-4000-8000-000000000005')),'ready','editing keeps ready status');
SELECT is((SELECT language FROM public.file_transcript('a7140000-0000-4000-8000-000000000005')),'en','editing preserves source language');
SELECT is((SELECT revision FROM public.update_file_transcript('a7140000-0000-4000-8000-000000000005','Corrected course transcript. 😀',0)),1,'retry after lost response is idempotent');
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005','stale overwrite',0)$$,'40001','transcript_conflict','stale edits cannot overwrite a newer version');
SELECT is((SELECT transcript FROM public.file_transcript('a7140000-0000-4000-8000-000000000005')),'Corrected course transcript. 😀','conflict preserves saved text');
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000002',true);
SELECT is((SELECT transcript FROM public.file_transcript('a7140000-0000-4000-8000-000000000005')),'Corrected course transcript. 😀','member reads correction with downloads disabled');
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000001',true);
SELECT is((SELECT length(transcript) FROM public.update_file_transcript('a7140000-0000-4000-8000-000000000005',repeat('x',200000),1)),200000,'maximum transcript length accepted');
SELECT is((SELECT transcript FROM public.update_file_transcript('a7140000-0000-4000-8000-000000000005','',2)),'','uploader may intentionally clear text');
RESET ROLE;
UPDATE public.memberships SET role='member' WHERE group_id='a7140000-0000-4000-8000-000000000004' AND user_id='a7140000-0000-4000-8000-000000000001';
DELETE FROM public.memberships WHERE group_id='a7140000-0000-4000-8000-000000000004' AND user_id='a7140000-0000-4000-8000-000000000001';
SET LOCAL ROLE authenticated;
SELECT throws_ok($$SELECT public.update_file_transcript('a7140000-0000-4000-8000-000000000005','left group',3)$$,'42501','not_file_uploader','former member cannot edit even their own recording');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
