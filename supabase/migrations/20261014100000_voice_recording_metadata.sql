-- Voice clips use the same private objects, stable upload IDs and owner-authorized cleanup.
-- Recognition/transcription is intentionally deferred; no jobs or transcript columns are added.
ALTER TABLE public.shared_files
    ADD COLUMN duration_ms BIGINT,
    ADD COLUMN is_voice_recording BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT shared_file_duration_positive CHECK (duration_ms IS NULL OR duration_ms > 0),
    ADD CONSTRAINT voice_recording_metadata_valid CHECK (
        NOT is_voice_recording OR (
            lower(mime_type) LIKE 'audio/%' AND duration_ms IS NOT NULL AND duration_ms BETWEEN 1 AND 600000
        )
    );

-- Defaults let older clients keep their seven named arguments. A single RPC signature avoids
-- PostgREST overload ambiguity while new clients include the immutable clip metadata.
DROP FUNCTION public.finalize_group_file(UUID, UUID, TEXT, TEXT, BIGINT, TEXT, BOOLEAN);
CREATE FUNCTION public.finalize_group_file(
    p_upload_id UUID, p_group_id UUID, p_file_name TEXT, p_mime_type TEXT,
    p_size_bytes BIGINT, p_storage_path TEXT, p_allow_downloads BOOLEAN,
    p_duration_ms BIGINT DEFAULT NULL, p_is_voice_recording BOOLEAN DEFAULT false
)
RETURNS SETOF public.shared_files
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_user UUID := auth.uid(); v_existing public.shared_files%ROWTYPE; v_metadata JSONB;
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000'; END IF;
    PERFORM 1 FROM public.groups WHERE id = p_group_id FOR UPDATE;
    IF NOT FOUND OR NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    IF p_upload_id IS NULL OR p_storage_path IS DISTINCT FROM p_group_id::text || '/' || v_user::text || '/' || p_upload_id::text
       OR p_file_name IS NULL OR char_length(btrim(p_file_name)) NOT BETWEEN 1 AND 255
       OR p_mime_type IS NULL OR char_length(p_mime_type) NOT BETWEEN 1 AND 255
       OR p_size_bytes IS NULL OR p_size_bytes NOT BETWEEN 1 AND 20971520 OR p_allow_downloads IS NULL
       OR p_is_voice_recording IS NULL OR (p_duration_ms IS NOT NULL AND p_duration_ms <= 0)
       OR (p_is_voice_recording AND (lower(p_mime_type) NOT LIKE 'audio/%' OR p_duration_ms IS NULL OR p_duration_ms > 600000)) THEN
        RAISE EXCEPTION 'invalid_file' USING ERRCODE = '22023';
    END IF;
    IF EXISTS (SELECT 1 FROM public.group_file_cleanup_jobs WHERE storage_path = p_storage_path OR file_id = p_upload_id) THEN
        RAISE EXCEPTION 'file_deleted' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO v_existing FROM public.shared_files WHERE id = p_upload_id;
    IF FOUND THEN
        IF v_existing.uploader_id <> v_user OR v_existing.group_id <> p_group_id
           OR v_existing.storage_path <> p_storage_path OR v_existing.file_name <> p_file_name
           OR v_existing.mime_type <> p_mime_type OR v_existing.size_bytes <> p_size_bytes
           OR v_existing.allow_downloads <> p_allow_downloads
           OR v_existing.duration_ms IS DISTINCT FROM p_duration_ms
           OR v_existing.is_voice_recording IS DISTINCT FROM p_is_voice_recording THEN
            RAISE EXCEPTION 'upload_identity_conflict' USING ERRCODE = '22023';
        END IF;
        RETURN NEXT v_existing;
        RETURN;
    END IF;
    SELECT metadata INTO v_metadata FROM storage.objects WHERE bucket_id = 'group-files' AND name = p_storage_path;
    IF NOT FOUND THEN RAISE EXCEPTION 'object_not_found' USING ERRCODE = 'P0002'; END IF;
    IF coalesce(v_metadata->>'size', '') !~ '^[0-9]+$'
       OR (v_metadata->>'size')::numeric <> p_size_bytes
       OR v_metadata->>'mimetype' IS DISTINCT FROM p_mime_type THEN
        RAISE EXCEPTION 'object_metadata_mismatch' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY INSERT INTO public.shared_files (
        id, group_id, uploader_id, file_name, mime_type, size_bytes, storage_path, allow_downloads, duration_ms, is_voice_recording
    ) VALUES (
        p_upload_id, p_group_id, v_user, p_file_name, p_mime_type, p_size_bytes, p_storage_path, p_allow_downloads, p_duration_ms, p_is_voice_recording
    ) RETURNING *;
END;
$$;
REVOKE ALL ON FUNCTION public.finalize_group_file(UUID, UUID, TEXT, TEXT, BIGINT, TEXT, BOOLEAN, BIGINT, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.finalize_group_file(UUID, UUID, TEXT, TEXT, BIGINT, TEXT, BOOLEAN, BIGINT, BOOLEAN) TO authenticated;

-- Keep every previous response field, nickname handling and membership requirement intact.
DROP FUNCTION public.group_files(UUID);
DROP FUNCTION private.group_files(UUID);
CREATE FUNCTION private.group_files(p_group_id UUID)
RETURNS TABLE (
    id UUID, group_id UUID, uploader_id UUID, uploader_name TEXT, file_name TEXT,
    mime_type TEXT, size_bytes BIGINT, storage_path TEXT, is_private BOOLEAN,
    created_at TIMESTAMPTZ, allow_downloads BOOLEAN, duration_ms BIGINT, is_voice_recording BOOLEAN
)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
#variable_conflict use_column
BEGIN
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY
    SELECT f.id, f.group_id, f.uploader_id, coalesce(gm.nickname, p.display_name, 'Member'),
           f.file_name, f.mime_type, f.size_bytes, f.storage_path, f.is_private, f.created_at,
           f.allow_downloads, f.duration_ms, f.is_voice_recording
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
    created_at TIMESTAMPTZ, allow_downloads BOOLEAN, duration_ms BIGINT, is_voice_recording BOOLEAN
)
LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
    SELECT * FROM private.group_files(p_group_id);
$$;
REVOKE ALL ON FUNCTION private.group_files(UUID), public.group_files(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.group_files(UUID), public.group_files(UUID) TO authenticated;
NOTIFY pgrst, 'reload schema';
