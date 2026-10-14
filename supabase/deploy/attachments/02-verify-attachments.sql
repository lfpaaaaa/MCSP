-- Read-only verification. All rows should return true.
SELECT 'download_consent_column' AS check_name, EXISTS (
    SELECT 1 FROM information_schema.columns WHERE table_schema='public'
    AND table_name='shared_files' AND column_name='allow_downloads'
    AND data_type='boolean' AND is_nullable='NO'
) AS passed
UNION ALL SELECT 'private_20mb_bucket', EXISTS (
    SELECT 1 FROM storage.buckets WHERE id='group-files' AND NOT public AND file_size_limit=20971520
)
UNION ALL SELECT 'file_list_rpc', to_regprocedure('public.group_files(uuid)') IS NOT NULL
UNION ALL SELECT 'file_access_rpc', to_regprocedure('public.group_file_access(uuid,boolean)') IS NOT NULL
UNION ALL SELECT 'download_consent_policy', EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
    AND policyname='Members can read group files' AND qual LIKE '%can_download_group_file%'
)
UNION ALL SELECT 'upload_policy', EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='storage' AND tablename='objects'
    AND policyname='Members can upload to their own group folder' AND cmd='INSERT'
)
UNION ALL SELECT 'record_insert_permission', has_column_privilege('authenticated','public.shared_files','allow_downloads','INSERT')
UNION ALL SELECT 'anonymous_access_denied', NOT has_function_privilege('anon','public.group_file_access(uuid,boolean)','EXECUTE');
