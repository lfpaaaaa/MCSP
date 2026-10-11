-- Download consent is per attachment, independent of preview and group membership.
ALTER TABLE public.shared_files ADD COLUMN allow_downloads BOOLEAN NOT NULL DEFAULT true;
GRANT INSERT (allow_downloads) ON public.shared_files TO authenticated;
COMMENT ON COLUMN public.shared_files.allow_downloads IS
    'Uploader consent for other group members to download. Members may still preview in the app.';

-- Preserve the existing RPC name and older response columns for previous clients.
DROP FUNCTION public.group_files(UUID);
DROP FUNCTION private.group_files(UUID);
CREATE FUNCTION private.group_files(p_group_id UUID)
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
    SELECT f.id, f.group_id, f.uploader_id, coalesce(gm.nickname, p.display_name, 'Member'),
           f.file_name, f.mime_type, f.size_bytes, f.storage_path, f.is_private, f.created_at, f.allow_downloads
    FROM public.shared_files f
    LEFT JOIN public.memberships gm ON gm.group_id = f.group_id AND gm.user_id = f.uploader_id
    LEFT JOIN public.profiles p ON p.id = f.uploader_id
    WHERE f.group_id = p_group_id ORDER BY f.created_at DESC, f.id DESC;
END;
$$;
CREATE FUNCTION public.group_files(p_group_id UUID)
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
CREATE FUNCTION private.can_download_group_file(p_object_name TEXT)
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
CREATE FUNCTION private.group_file_access(p_file_id UUID, p_download BOOLEAN)
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
CREATE FUNCTION public.group_file_access(p_file_id UUID, p_download BOOLEAN)
RETURNS TEXT LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
    SELECT private.group_file_access(p_file_id, p_download);
$$;
REVOKE ALL ON FUNCTION private.group_file_access(UUID, BOOLEAN), public.group_file_access(UUID, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.group_file_access(UUID, BOOLEAN), public.group_file_access(UUID, BOOLEAN) TO authenticated;
