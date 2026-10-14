BEGIN;
SELECT no_plan();

INSERT INTO auth.users (id,email,raw_user_meta_data) VALUES
('a7140000-0000-4000-8000-000000000001','delivery-owner@example.test','{"display_name":"Owner"}'),
('a7140000-0000-4000-8000-000000000002','delivery-member@example.test','{"display_name":"Member"}'),
('a7140000-0000-4000-8000-000000000003','delivery-outsider@example.test','{}');
INSERT INTO public.groups (id,name,created_by) VALUES
('a7140000-0000-4000-8000-000000000004','Delivery fixture','a7140000-0000-4000-8000-000000000001');
INSERT INTO public.memberships (group_id,user_id,role) VALUES
('a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000001','owner'),
('a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000002','member');
INSERT INTO storage.objects (bucket_id,name,metadata) VALUES
('group-files','a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000005','{"size":5,"mimetype":"text/plain"}'),
('group-files','a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/unfinished-upload','{"size":5,"mimetype":"text/plain"}');

SELECT ok(NOT has_table_privilege('authenticated','public.group_file_cleanup_jobs','SELECT'), 'cleanup paths are server only');
SELECT ok(NOT has_function_privilege('authenticated','public.finish_group_file_cleanup(uuid,text)','EXECUTE'), 'clients cannot claim cleanup succeeded');
SELECT ok(NOT has_function_privilege('anon','public.finalize_group_file(uuid,uuid,text,text,bigint,text,boolean,bigint,boolean)','EXECUTE'), 'anonymous finalization is forbidden');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000002',true);
SELECT lives_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000005','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000005',false)$$, 'member finalizes owned upload');
SELECT lives_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000005','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000005',false)$$, 'lost response retries same finalization');
SELECT is((SELECT count(*) FROM public.shared_files),1::bigint,'only one file is created');
RESET ROLE;
INSERT INTO storage.objects (bucket_id,name,metadata) VALUES
('group-files','a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000006','{"size":10,"mimetype":"text/plain"}');
SET LOCAL ROLE authenticated;
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000006','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000006',false)$$,'22023','object_metadata_mismatch','registration checks stored object size');
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000006','a7140000-0000-4000-8000-000000000004','clip.txt','application/pdf',10,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000006',false)$$,'22023','object_metadata_mismatch','registration checks stored object MIME type');
RESET ROLE;
DELETE FROM public.memberships WHERE group_id='a7140000-0000-4000-8000-000000000004' AND user_id='a7140000-0000-4000-8000-000000000002';
SET LOCAL ROLE authenticated;
SELECT throws_ok($$SELECT public.prepare_group_file_deletion('a7140000-0000-4000-8000-000000000005')$$,'42501','not_a_member','removed uploader cannot request deletion');
DELETE FROM public.shared_files WHERE id='a7140000-0000-4000-8000-000000000005';
RESET ROLE;
SELECT is((SELECT count(*) FROM public.shared_files),1::bigint,'removed uploader cannot bypass membership by deleting metadata directly');
INSERT INTO public.memberships (group_id,user_id,role) VALUES ('a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000002','member');
SET LOCAL ROLE authenticated;
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000005','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000005',true)$$,'22023','upload_identity_conflict','retry cannot change download consent');
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000006','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000001/a7140000-0000-4000-8000-000000000006',false)$$,'22023','invalid_file','another uploader path is rejected');
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000007','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000007',false)$$,'P0002','object_not_found','missing object cannot be registered');

SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000001',true);
SELECT throws_ok($$SELECT public.prepare_group_file_deletion('a7140000-0000-4000-8000-000000000005')$$,'42501','not_the_uploader','group owner cannot delete member upload');
SELECT throws_ok($$SELECT public.group_file_access('a7140000-0000-4000-8000-000000000005',true)$$,'42501','downloads_disabled','cleanup does not weaken download consent');
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000003',true);
SELECT throws_ok($$SELECT public.prepare_group_file_deletion('a7140000-0000-4000-8000-000000000005')$$,'42501','not_a_member','outsider cannot request cleanup');
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000005','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000003/a7140000-0000-4000-8000-000000000005',false)$$,'42501','not_a_member','outsider cannot finalize');
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000002',true);
SELECT set_config('test.cleanup_job',public.prepare_group_file_deletion('a7140000-0000-4000-8000-000000000005')->>'id',true);
SELECT is((SELECT count(*) FROM public.shared_files),1::bigint,'request retains metadata while object deletion is pending');
RESET ROLE;

SET LOCAL ROLE service_role;
SELECT public.finish_group_file_cleanup(current_setting('test.cleanup_job')::uuid,'storage_unavailable');
SELECT is((SELECT attempts FROM public.group_file_cleanup_jobs WHERE id=current_setting('test.cleanup_job')::uuid),1,'cleanup failure is observable');
SELECT is((SELECT state FROM public.group_file_cleanup_jobs WHERE id=current_setting('test.cleanup_job')::uuid),'pending','failed cleanup stays durable');
RESET ROLE;
SELECT is((SELECT count(*) FROM public.shared_files),1::bigint,'failed storage cleanup retains file for retry');
SET LOCAL ROLE service_role;
SELECT public.finish_group_file_cleanup(current_setting('test.cleanup_job')::uuid);
SELECT lives_ok($$SELECT public.finish_group_file_cleanup(current_setting('test.cleanup_job')::uuid)$$,'lost completion reply can be retried');
SELECT is((SELECT state FROM public.group_file_cleanup_jobs WHERE id=current_setting('test.cleanup_job')::uuid),'completed','successful cleanup keeps an idempotence tombstone');
RESET ROLE;
SELECT is((SELECT count(*) FROM public.shared_files),0::bigint,'successful cleanup removes metadata');
SELECT is((SELECT count(*) FROM storage.objects WHERE bucket_id='group-files'),3::bigint,'SQL never deletes storage metadata: worker must use Storage API');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000002',true);
SELECT is(public.prepare_group_file_deletion('a7140000-0000-4000-8000-000000000005')->>'state','completed','uploader retries after server completion');
SELECT throws_ok($$SELECT * FROM public.finalize_group_file('a7140000-0000-4000-8000-000000000005','a7140000-0000-4000-8000-000000000004','clip.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/a7140000-0000-4000-8000-000000000005',false)$$,'22023','file_deleted','retry cannot resurrect a deleted upload');
RESET ROLE;
INSERT INTO public.shared_files (id,group_id,uploader_id,file_name,mime_type,size_bytes,storage_path) VALUES
('a7140000-0000-4000-8000-000000000008','a7140000-0000-4000-8000-000000000004','a7140000-0000-4000-8000-000000000002','legacy.txt','text/plain',5,'a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/legacy-object');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7140000-0000-4000-8000-000000000001',true);
SELECT public.dissolve_group('a7140000-0000-4000-8000-000000000004');
RESET ROLE;
SELECT is((SELECT count(*) FROM public.group_file_cleanup_jobs WHERE state='pending'),3::bigint,'dissolution queues unregistered objects too');
SELECT ok(EXISTS (SELECT 1 FROM public.group_file_cleanup_jobs WHERE state='pending' AND storage_path='a7140000-0000-4000-8000-000000000004/a7140000-0000-4000-8000-000000000002/unfinished-upload'),'cleanup path survives group removal');
SELECT ok(EXISTS (SELECT 1 FROM public.group_file_cleanup_jobs WHERE state='pending' AND file_id='a7140000-0000-4000-8000-000000000008'),'cascaded legacy file metadata queues cleanup even without a stored object');

SELECT * FROM finish();
ROLLBACK;
