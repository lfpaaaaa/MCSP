-- Target: MCSP attachment sharing, integrate/context-engine-sensing-main (086240d).
-- Run as the project database owner in Supabase SQL Editor. Safe to rerun.
-- Repairs the existing group-file service; does not delete messages, groups or file records.
-- Derived from 20260925070000_group_files and 20261011170000_attachment_download_permissions.
-- Does not change migration history. Reconcile manual deployment with your migration workflow.
BEGIN;
DO $preflight$
BEGIN
    IF to_regclass('public.shared_files') IS NULL
       OR to_regclass('public.memberships') IS NULL
       OR to_regclass('public.profiles') IS NULL
       OR to_regprocedure('private.is_group_member(uuid)') IS NULL THEN
        RAISE EXCEPTION 'Missing base group schema. Apply the repository base migrations first.';
    END IF;
END;
$preflight$;

-- Shared files. Objects are kept in the private group-files bucket under
-- <group id>/<uploader id>/<object id>. Members of a group can read its objects, and each member
-- can only add or remove objects in their own folder. File details stay in public.shared_files.

INSERT INTO storage.buckets (id, name, public, file_size_limit)
VALUES ('group-files', 'group-files', false, 20971520)
ON CONFLICT (id) DO UPDATE
SET public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit;

-- ---------------------------------------------------------------------------
-- Storage access
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION private.is_member_of_object_group(p_object_name TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    -- Compared as text, so a first folder that is not a group id is refused instead of failing.
    SELECT EXISTS (
        SELECT 1
        FROM public.memberships AS m
        WHERE m.user_id = (SELECT auth.uid())
          AND m.group_id::TEXT = split_part(p_object_name, '/', 1)
    );
$$;

COMMENT ON FUNCTION private.is_member_of_object_group(TEXT) IS
    'True when the signed-in user belongs to the group named by the first folder of a storage object path.';

REVOKE ALL ON FUNCTION private.is_member_of_object_group(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.is_member_of_object_group(TEXT) TO authenticated;

DROP POLICY IF EXISTS "Members can read group files" ON storage.objects;
CREATE POLICY "Members can read group files"
    ON storage.objects
    FOR SELECT
    TO authenticated
    USING (bucket_id = 'group-files' AND private.is_member_of_object_group(name));

DROP POLICY IF EXISTS "Members can upload to their own group folder" ON storage.objects;
CREATE POLICY "Members can upload to their own group folder"
    ON storage.objects
    FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'group-files'
        AND split_part(name, '/', 2) = (SELECT auth.uid())::TEXT
        AND private.is_member_of_object_group(name)
    );

DROP POLICY IF EXISTS "Uploaders can delete their own group files" ON storage.objects;
CREATE POLICY "Uploaders can delete their own group files"
    ON storage.objects
    FOR DELETE
    TO authenticated
    USING (bucket_id = 'group-files' AND split_part(name, '/', 2) = (SELECT auth.uid())::TEXT);


-- Download consent is per attachment, independent of preview and group membership.
ALTER TABLE public.shared_files ADD COLUMN IF NOT EXISTS allow_downloads BOOLEAN NOT NULL DEFAULT true;
GRANT INSERT (allow_downloads) ON public.shared_files TO authenticated;
COMMENT ON COLUMN public.shared_files.allow_downloads IS
    'Uploader consent for other group members to download. Members may still preview in the app.';

-- Preserve the existing RPC name and older response columns for previous clients.
DROP FUNCTION IF EXISTS public.group_files(UUID);
DROP FUNCTION IF EXISTS private.group_files(UUID);
CREATE OR REPLACE FUNCTION private.group_files(p_group_id UUID)
RETURNS TABLE (
    id UUID, group_id UUID, uploader_id UUID, uploader_name TEXT, file_name TEXT,
    mime_type TEXT, size_bytes BIGINT, storage_path TEXT, is_private BOOLEAN,
    created_at TIMESTAMPTZ, allow_downloads BOOLEAN
)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT f.id, f.group_id, f.uploader_id, coalesce(nullif(to_jsonb(gm)->>'nickname', ''), p.display_name, 'Member'),
           f.file_name, f.mime_type, f.size_bytes, f.storage_path, f.is_private, f.created_at, f.allow_downloads
    FROM public.shared_files f
    LEFT JOIN public.memberships gm ON gm.group_id = f.group_id AND gm.user_id = f.uploader_id
    LEFT JOIN public.profiles p ON p.id = f.uploader_id
    WHERE f.group_id = p_group_id ORDER BY f.created_at DESC, f.id DESC;
END;
$$;
CREATE OR REPLACE FUNCTION public.group_files(p_group_id UUID)
RETURNS TABLE (
    id UUID, group_id UUID, uploader_id UUID, uploader_name TEXT, file_name TEXT,
    mime_type TEXT, size_bytes BIGINT, storage_path TEXT, is_private BOOLEAN,
    created_at TIMESTAMPTZ, allow_downloads BOOLEAN
)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
    SELECT * FROM private.group_files(p_group_id);
$$;
REVOKE ALL ON FUNCTION private.group_files(UUID), public.group_files(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.group_files(UUID), public.group_files(UUID) TO authenticated;

-- Direct Storage reads/signing must not bypass the download permission.
CREATE OR REPLACE FUNCTION private.can_download_group_file(p_object_name TEXT)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.shared_files f
        JOIN public.memberships m ON m.group_id = f.group_id AND m.user_id = (SELECT auth.uid())
        WHERE f.storage_path = p_object_name
          AND (f.allow_downloads OR f.uploader_id = (SELECT auth.uid()))
    );
$$;
REVOKE ALL ON FUNCTION private.can_download_group_file(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.can_download_group_file(TEXT) TO authenticated;
ALTER POLICY "Members can read group files" ON storage.objects
    USING (bucket_id = 'group-files' AND private.can_download_group_file(name));

-- The Edge Function calls this as the requesting user, then signs with its server credential.
CREATE OR REPLACE FUNCTION private.group_file_access(p_file_id UUID, p_download BOOLEAN)
RETURNS TEXT LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE f public.shared_files%ROWTYPE;
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '42501';
    END IF;
    IF p_download IS NULL THEN
        RAISE EXCEPTION 'invalid_purpose' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO f FROM public.shared_files WHERE id = p_file_id;
    IF NOT FOUND OR NOT private.is_group_member(f.group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    IF p_download AND NOT f.allow_downloads AND f.uploader_id <> auth.uid() THEN
        RAISE EXCEPTION 'downloads_disabled' USING ERRCODE = '42501';
    END IF;
    RETURN f.storage_path;
END;
$$;
CREATE OR REPLACE FUNCTION public.group_file_access(p_file_id UUID, p_download BOOLEAN)
RETURNS TEXT LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
    SELECT private.group_file_access(p_file_id, p_download);
$$;
REVOKE ALL ON FUNCTION private.group_file_access(UUID, BOOLEAN), public.group_file_access(UUID, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.group_file_access(UUID, BOOLEAN), public.group_file_access(UUID, BOOLEAN) TO authenticated;

-- Reinstate the existing file-record contract with group and uploader path checks.
ALTER TABLE public.shared_files ENABLE ROW LEVEL SECURITY;
GRANT SELECT, DELETE ON public.shared_files TO authenticated;
GRANT INSERT (group_id, file_name, mime_type, size_bytes, storage_path, is_private, allow_downloads)
    ON public.shared_files TO authenticated;
DROP POLICY IF EXISTS "Members can add file records as themselves" ON public.shared_files;
CREATE POLICY "Members can add file records as themselves" ON public.shared_files
    FOR INSERT TO authenticated WITH CHECK (
        uploader_id = (SELECT auth.uid())
        AND split_part(storage_path, '/', 1) = group_id::TEXT
        AND split_part(storage_path, '/', 2) = (SELECT auth.uid())::TEXT
        AND private.is_group_member(group_id)
    );
NOTIFY pgrst, 'reload schema';
COMMIT;
