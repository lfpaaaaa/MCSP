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

CREATE FUNCTION private.is_member_of_object_group(p_object_name TEXT)
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

CREATE POLICY "Members can read group files"
    ON storage.objects
    FOR SELECT
    TO authenticated
    USING (bucket_id = 'group-files' AND private.is_member_of_object_group(name));

CREATE POLICY "Members can upload to their own group folder"
    ON storage.objects
    FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'group-files'
        AND split_part(name, '/', 2) = (SELECT auth.uid())::TEXT
        AND private.is_member_of_object_group(name)
    );

CREATE POLICY "Uploaders can delete their own group files"
    ON storage.objects
    FOR DELETE
    TO authenticated
    USING (bucket_id = 'group-files' AND split_part(name, '/', 2) = (SELECT auth.uid())::TEXT);

-- File records must point into the uploader's own folder.
ALTER POLICY "Members can add file records as themselves"
    ON public.shared_files
    WITH CHECK (
        (SELECT auth.uid()) = uploader_id
        AND split_part(storage_path, '/', 2) = (SELECT auth.uid())::TEXT
        AND private.is_group_member(group_id)
    );

-- ---------------------------------------------------------------------------
-- File list with uploader names
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.group_files(p_group_id UUID)
RETURNS TABLE (
    id UUID,
    group_id UUID,
    uploader_id UUID,
    uploader_name TEXT,
    file_name TEXT,
    mime_type TEXT,
    size_bytes BIGINT,
    storage_path TEXT,
    is_private BOOLEAN,
    created_at TIMESTAMPTZ
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
#variable_conflict use_column
BEGIN
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT f.id, f.group_id, f.uploader_id, coalesce(p.display_name, 'Member'), f.file_name,
           f.mime_type, f.size_bytes, f.storage_path, f.is_private, f.created_at
    FROM public.shared_files AS f
    LEFT JOIN public.profiles AS p ON p.id = f.uploader_id
    WHERE f.group_id = p_group_id
    ORDER BY f.created_at DESC, f.id DESC;
END;
$$;

CREATE FUNCTION public.group_files(p_group_id UUID)
RETURNS TABLE (
    id UUID,
    group_id UUID,
    uploader_id UUID,
    uploader_name TEXT,
    file_name TEXT,
    mime_type TEXT,
    size_bytes BIGINT,
    storage_path TEXT,
    is_private BOOLEAN,
    created_at TIMESTAMPTZ
)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.group_files(p_group_id);
$$;

COMMENT ON FUNCTION public.group_files(UUID) IS
    'Files shared in a group the signed-in user belongs to, newest first, with the uploader''s display name.';

REVOKE ALL ON FUNCTION private.group_files(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.group_files(UUID) TO authenticated;
REVOKE ALL ON FUNCTION public.group_files(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.group_files(UUID) TO authenticated;
