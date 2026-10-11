BEGIN;
SELECT plan(17);
INSERT INTO auth.users (id, email, raw_app_meta_data, raw_user_meta_data, created_at, updated_at) VALUES
('a7110000-0000-4000-8000-000000000001', 'download-owner@example.test', '{"provider":"email"}', '{"display_name":"Owner"}', now(), now()),
('a7110000-0000-4000-8000-000000000002', 'download-member@example.test', '{"provider":"email"}', '{"display_name":"Member"}', now(), now()),
('a7110000-0000-4000-8000-000000000003', 'download-outsider@example.test', '{"provider":"email"}', '{"display_name":"Outsider"}', now(), now());
INSERT INTO public.groups (id, name, created_by) VALUES ('a7110000-0000-4000-8000-000000000004', 'Download permission fixture', 'a7110000-0000-4000-8000-000000000001');
INSERT INTO public.memberships (group_id,user_id,role) VALUES ('a7110000-0000-4000-8000-000000000004','a7110000-0000-4000-8000-000000000001','owner'),('a7110000-0000-4000-8000-000000000004','a7110000-0000-4000-8000-000000000002','member');
INSERT INTO public.shared_files (id,group_id,uploader_id,file_name,mime_type,size_bytes,storage_path,allow_downloads) VALUES
('a7110000-0000-4000-8000-000000000005','a7110000-0000-4000-8000-000000000004','a7110000-0000-4000-8000-000000000001','restricted.txt','text/plain',5,'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/restricted',false),
('a7110000-0000-4000-8000-000000000007','a7110000-0000-4000-8000-000000000004','a7110000-0000-4000-8000-000000000002','member.txt','text/plain',5,'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000002/restricted',false);
INSERT INTO public.shared_files (id,group_id,uploader_id,file_name,mime_type,size_bytes,storage_path) VALUES
('a7110000-0000-4000-8000-000000000006','a7110000-0000-4000-8000-000000000004','a7110000-0000-4000-8000-000000000001','allowed.txt','text/plain',5,'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/allowed');
SELECT ok((SELECT allow_downloads FROM public.shared_files WHERE id='a7110000-0000-4000-8000-000000000006'), 'existing/default uploads permit downloads');
SELECT ok((SELECT NOT public FROM storage.buckets WHERE id='group-files'), 'bucket remains non-public');
SELECT ok(NOT has_function_privilege('anon','public.group_file_access(uuid,boolean)','EXECUTE'), 'anonymous access is denied');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7110000-0000-4000-8000-000000000001',true);
SELECT is(public.group_file_access('a7110000-0000-4000-8000-000000000005',true),'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/restricted','uploader can download own restricted file');
SELECT throws_ok($$SELECT public.group_file_access('a7110000-0000-4000-8000-000000000007',true)$$,'42501','downloads_disabled','group owner cannot override another uploader');
SELECT is((SELECT allow_downloads FROM public.group_files('a7110000-0000-4000-8000-000000000004') WHERE id='a7110000-0000-4000-8000-000000000005'),false,'list persists per-file consent');
SELECT set_config('request.jwt.claim.sub','a7110000-0000-4000-8000-000000000002',true);
SELECT is(public.group_file_access('a7110000-0000-4000-8000-000000000005',false),'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/restricted','member can preview restricted attachment');
SELECT throws_ok($$SELECT public.group_file_access('a7110000-0000-4000-8000-000000000005',true)$$,'42501','downloads_disabled','member cannot download restricted attachment');
SELECT ok(NOT private.can_download_group_file('a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/restricted'),'direct storage signing cannot bypass consent');
SELECT ok(private.can_download_group_file('a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/allowed'),'allowed file remains downloadable');
SELECT is(public.group_file_access('a7110000-0000-4000-8000-000000000007',true),'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000002/restricted','member uploader can download own attachment');
SELECT throws_ok($$SELECT public.group_file_access('a7110000-0000-4000-8000-000000000005',NULL)$$,'22023','invalid_purpose','missing purpose cannot bypass check');
SELECT throws_ok($$UPDATE public.shared_files SET allow_downloads=true WHERE id='a7110000-0000-4000-8000-000000000005'$$,'42501','permission denied for table shared_files','recipient cannot change uploader consent');
SELECT lives_ok($$INSERT INTO public.shared_files (group_id,file_name,mime_type,size_bytes,storage_path,allow_downloads) VALUES ('a7110000-0000-4000-8000-000000000004','new.txt','text/plain',5,'a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000002/new',false)$$,'uploader can choose consent on insert');
SELECT set_config('request.jwt.claim.sub','a7110000-0000-4000-8000-000000000003',true);
SELECT throws_ok($$SELECT public.group_file_access('a7110000-0000-4000-8000-000000000005',false)$$,'42501','not_a_member','outsider cannot preview');
SELECT throws_ok($$SELECT public.group_file_access('a7110000-0000-4000-8000-000000000006',true)$$,'42501','not_a_member','outsider cannot download even when allowed');
SELECT ok(NOT private.can_download_group_file('a7110000-0000-4000-8000-000000000004/a7110000-0000-4000-8000-000000000001/allowed'),'outsider has no direct storage access');
RESET ROLE;
SELECT * FROM finish();
ROLLBACK;
