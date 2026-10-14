BEGIN;
SELECT no_plan();

INSERT INTO auth.users (id,email,raw_user_meta_data) VALUES
('a7150000-0000-4000-8000-000000000001','voice-owner@example.test','{"display_name":"Owner"}'),
('a7150000-0000-4000-8000-000000000002','voice-member@example.test','{"display_name":"Member"}'),
('a7150000-0000-4000-8000-000000000003','voice-outsider@example.test','{}');
INSERT INTO public.groups (id,name,created_by) VALUES
('a7150000-0000-4000-8000-000000000004','Voice fixture','a7150000-0000-4000-8000-000000000001');
INSERT INTO public.memberships (group_id,user_id,role) VALUES
('a7150000-0000-4000-8000-000000000004','a7150000-0000-4000-8000-000000000001','owner'),
('a7150000-0000-4000-8000-000000000004','a7150000-0000-4000-8000-000000000002','member');
INSERT INTO storage.objects (bucket_id,name,metadata) VALUES
('group-files','a7150000-0000-4000-8000-000000000004/a7150000-0000-4000-8000-000000000002/a7150000-0000-4000-8000-000000000005','{"size":5,"mimetype":"audio/mp4"}'),
('group-files','a7150000-0000-4000-8000-000000000004/a7150000-0000-4000-8000-000000000002/a7150000-0000-4000-8000-000000000006','{"size":5,"mimetype":"audio/mp4"}'),
('group-files','a7150000-0000-4000-8000-000000000004/a7150000-0000-4000-8000-000000000002/a7150000-0000-4000-8000-000000000007','{"size":5,"mimetype":"text/plain"}'),
('group-files','a7150000-0000-4000-8000-000000000004/a7150000-0000-4000-8000-000000000002/a7150000-0000-4000-8000-000000000008','{"size":5,"mimetype":"audio/mp4"}');

CREATE FUNCTION pg_temp.finalize_voice(p_id UUID, p_duration BIGINT, p_voice BOOLEAN DEFAULT true, p_mime TEXT DEFAULT 'audio/mp4')
RETURNS SETOF public.shared_files LANGUAGE sql AS $$
    SELECT * FROM public.finalize_group_file(p_id,'a7150000-0000-4000-8000-000000000004','voice.m4a',p_mime,5,
        'a7150000-0000-4000-8000-000000000004/a7150000-0000-4000-8000-000000000002/' || p_id::text,false,p_duration,p_voice);
$$;

SELECT has_column('public','shared_files','duration_ms','shared files have optional duration');
SELECT has_column('public','shared_files','is_voice_recording','shared files identify recordings separately from imported audio');
SELECT ok(to_regprocedure('public.finalize_group_file(uuid,uuid,text,text,bigint,text,boolean)') IS NULL,'obsolete overload is removed');
SELECT is((SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public' AND p.proname='finalize_group_file'),1::bigint,'PostgREST sees one unambiguous finalization signature');
SELECT ok(NOT has_function_privilege('anon','public.finalize_group_file(uuid,uuid,text,text,bigint,text,boolean,bigint,boolean)','EXECUTE'),'anonymous users cannot finalize recordings');

SET LOCAL ROLE authenticated;
SELECT set_config('request.jwt.claim.sub','a7150000-0000-4000-8000-000000000002',true);
SELECT lives_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000005',2000)$$,'member registers a voice recording');
SELECT lives_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000005',2000)$$,'lost response retries the same recording');
SELECT is((SELECT count(*) FROM public.shared_files),1::bigint,'retry creates one recording row');
SELECT is((SELECT duration_ms FROM public.group_files('a7150000-0000-4000-8000-000000000004') WHERE id='a7150000-0000-4000-8000-000000000005'),2000::bigint,'group file RPC returns recording duration');
SELECT ok((SELECT is_voice_recording FROM public.group_files('a7150000-0000-4000-8000-000000000004') WHERE id='a7150000-0000-4000-8000-000000000005'),'group file RPC returns voice flag');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000005',2001)$$,'22023','upload_identity_conflict','retry cannot change duration');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000005',2000,false)$$,'22023','upload_identity_conflict','retry cannot remove voice flag');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',NULL)$$,'22023','invalid_file','voice recording requires duration');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',600001)$$,'22023','invalid_file','recordings longer than ten minutes are rejected');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',0)$$,'22023','invalid_file','zero recording duration is rejected');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',-1)$$,'22023','invalid_file','negative recording duration is rejected');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',2000,true,'image/jpeg')$$,'22023','invalid_file','a voice flag cannot label a non-audio attachment');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',2000,NULL)$$,'22023','invalid_file','voice flag cannot be null');
SELECT lives_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',600000)$$,'exact ten-minute boundary is accepted');
SELECT lives_ok($$SELECT * FROM public.finalize_group_file('a7150000-0000-4000-8000-000000000007','a7150000-0000-4000-8000-000000000004','legacy.txt','text/plain',5,'a7150000-0000-4000-8000-000000000004/a7150000-0000-4000-8000-000000000002/a7150000-0000-4000-8000-000000000007',true)$$,'older clients still finalize with seven arguments');
SELECT ok((SELECT duration_ms IS NULL AND NOT is_voice_recording FROM public.shared_files WHERE id='a7150000-0000-4000-8000-000000000007'),'older uploads retain neutral metadata defaults');
SELECT lives_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000008',700000,false)$$,'imported audio is not capped by the recording limit');

SELECT set_config('request.jwt.claim.sub','a7150000-0000-4000-8000-000000000001',true);
SELECT throws_ok($$SELECT public.group_file_access('a7150000-0000-4000-8000-000000000005',true)$$,'42501','downloads_disabled','recordings preserve uploader download consent');
SELECT lives_ok($$SELECT public.group_file_access('a7150000-0000-4000-8000-000000000005',false)$$,'other members can request in-app recording playback');
SELECT throws_ok($$SELECT public.prepare_group_file_deletion('a7150000-0000-4000-8000-000000000005')$$,'42501','not_the_uploader','group ownership does not permit deleting another member recording');
SELECT set_config('request.jwt.claim.sub','a7150000-0000-4000-8000-000000000003',true);
SELECT throws_ok($$SELECT * FROM public.group_files('a7150000-0000-4000-8000-000000000004')$$,'42501','not_a_member','recording catalog remains member-only');
SELECT throws_ok($$SELECT * FROM pg_temp.finalize_voice('a7150000-0000-4000-8000-000000000006',2000)$$,'42501','not_a_member','outsider cannot finalize a recording');
RESET ROLE;

SELECT * FROM finish();
ROLLBACK;
