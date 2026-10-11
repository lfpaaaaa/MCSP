BEGIN;
SELECT plan(16);
INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
 ('ac110000-0000-4000-8000-000000000001','profile-edit-owner@example.com','{"display_name":"Original","avatar_url":"https://example.com/original.jpg"}'),
 ('ac110000-0000-4000-8000-000000000002','profile-edit-peer@example.com','{"display_name":"Peer"}'),
 ('ac110000-0000-4000-8000-000000000003','profile-edit-outsider@example.com','{"display_name":"Outsider"}');
INSERT INTO public.groups (id,name,created_by) VALUES
 ('ac110000-0000-4000-8000-000000000010','Profile edit fixture','ac110000-0000-4000-8000-000000000001');
INSERT INTO public.memberships (group_id,user_id,role,nickname) VALUES
 ('ac110000-0000-4000-8000-000000000010','ac110000-0000-4000-8000-000000000001','owner',NULL),
 ('ac110000-0000-4000-8000-000000000010','ac110000-0000-4000-8000-000000000002','member','My group name');
UPDATE auth.users SET raw_user_meta_data = raw_user_meta_data || '{"display_name":"New Account Name","avatar_url":"storage://profile-avatars/ac110000-0000-4000-8000-000000000001/ac110000-0000-4000-8000-000000000020.jpg"}'::jsonb
 WHERE id='ac110000-0000-4000-8000-000000000001';
SELECT is((SELECT display_name FROM public.profiles WHERE id='ac110000-0000-4000-8000-000000000001'),'New Account Name','Auth profile edits persist to the application profile');
SELECT is((SELECT avatar_url FROM public.profiles WHERE id='ac110000-0000-4000-8000-000000000001'),'storage://profile-avatars/ac110000-0000-4000-8000-000000000001/ac110000-0000-4000-8000-000000000020.jpg','Avatar reference is stored durably');
SELECT ok((SELECT NOT public FROM storage.buckets WHERE id='profile-avatars'),'Avatar bucket is private');
SELECT is((SELECT file_size_limit FROM storage.buckets WHERE id='profile-avatars'),1048576::bigint,'Avatar uploads are bounded');
SELECT is((SELECT allowed_mime_types FROM storage.buckets WHERE id='profile-avatars'),ARRAY['image/jpeg'],'Avatar bucket accepts JPEG only');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','ac110000-0000-4000-8000-000000000001',true);
SELECT lives_ok($$INSERT INTO storage.objects(bucket_id,name) VALUES ('profile-avatars','ac110000-0000-4000-8000-000000000001/ac110000-0000-4000-8000-000000000020.jpg')$$,'Owner can upload to own folder');
SELECT throws_ok($$INSERT INTO storage.objects(bucket_id,name) VALUES ('profile-avatars','ac110000-0000-4000-8000-000000000002/ac110000-0000-4000-8000-000000000021.jpg')$$,'42501',NULL,'Cannot upload into another account folder');
SELECT throws_ok($$INSERT INTO storage.objects(bucket_id,name) VALUES ('profile-avatars','ac110000-0000-4000-8000-000000000001/not-an-avatar.html')$$,'42501',NULL,'Invalid object paths are rejected');
SELECT is((SELECT count(*) FROM storage.objects WHERE bucket_id='profile-avatars' AND name LIKE 'ac110000-0000-4000-8000-000000000001/%'),1::bigint,'Owner can read own avatar');
SELECT set_config('request.jwt.claim.sub','ac110000-0000-4000-8000-000000000002',true);
SELECT is((SELECT count(*) FROM storage.objects WHERE bucket_id='profile-avatars' AND name LIKE 'ac110000-0000-4000-8000-000000000001/%'),1::bigint,'Group peer can read current avatar');
SELECT is((SELECT display_name FROM public.group_members('ac110000-0000-4000-8000-000000000010') WHERE user_id='ac110000-0000-4000-8000-000000000001'),'New Account Name','Group member list reads new global name');
SELECT is((SELECT display_name FROM public.group_members('ac110000-0000-4000-8000-000000000010') WHERE user_id=auth.uid()),'My group name','An existing group nickname stays independent');
SELECT set_config('request.jwt.claim.sub','ac110000-0000-4000-8000-000000000003',true);
SELECT is((SELECT count(*) FROM storage.objects WHERE bucket_id='profile-avatars' AND name LIKE 'ac110000-0000-4000-8000-000000000001/%'),0::bigint,'Unrelated account cannot read avatar');
RESET ROLE;
UPDATE auth.users SET raw_user_meta_data=raw_user_meta_data || '{"avatar_url":""}'::jsonb WHERE id='ac110000-0000-4000-8000-000000000001';
SELECT is((SELECT avatar_url FROM public.profiles WHERE id='ac110000-0000-4000-8000-000000000001'),'','Explicit avatar removal persists');
SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','ac110000-0000-4000-8000-000000000002',true);
SELECT is((SELECT count(*) FROM storage.objects WHERE bucket_id='profile-avatars' AND name LIKE 'ac110000-0000-4000-8000-000000000001/%'),0::bigint,'Previous avatar is no longer readable by peers');
RESET ROLE;
SELECT ok(NOT has_function_privilege('anon','private.can_read_profile_avatar(text)','EXECUTE'),'Anonymous users cannot read avatar access helper');
SELECT * FROM finish();
ROLLBACK;
