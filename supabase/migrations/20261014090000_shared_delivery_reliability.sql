-- Upload IDs are shared_files.id; the same immutable path is retried after lost responses.
-- Cleanup survives metadata/group removal. Only the service worker can drain these jobs.
CREATE TABLE public.group_file_cleanup_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_id UUID UNIQUE,
    group_id UUID NOT NULL,
    uploader_id UUID NOT NULL,
    storage_path TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'completed')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);
ALTER TABLE public.group_file_cleanup_jobs ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.group_file_cleanup_jobs FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.group_file_cleanup_jobs TO service_role;
CREATE INDEX group_file_cleanup_pending_idx ON public.group_file_cleanup_jobs (attempts, created_at, id) WHERE state = 'pending';

-- Older clients may still delete metadata directly; require current membership there too.
ALTER POLICY "Uploaders can delete their file records" ON public.shared_files
USING ((SELECT auth.uid()) = uploader_id AND private.is_group_member(group_id));
ALTER POLICY "Uploaders can delete their own group files" ON storage.objects
USING (bucket_id = 'group-files' AND split_part(name, '/', 2) = (SELECT auth.uid())::text
       AND private.is_member_of_object_group(name));

CREATE FUNCTION private.queue_deleted_group_file()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.group_file_cleanup_jobs (file_id, group_id, uploader_id, storage_path)
    VALUES (OLD.id, OLD.group_id, OLD.uploader_id, OLD.storage_path)
    ON CONFLICT (storage_path) DO NOTHING;
    RETURN OLD;
END;
$$;
CREATE TRIGGER queue_deleted_group_file BEFORE DELETE ON public.shared_files
FOR EACH ROW EXECUTE FUNCTION private.queue_deleted_group_file();

-- Include objects whose upload finished but whose file registration never did.
CREATE FUNCTION private.queue_dissolved_group_objects()
RETURNS TRIGGER LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    INSERT INTO public.group_file_cleanup_jobs (file_id, group_id, uploader_id, storage_path)
    SELECT f.id, OLD.id, coalesce(f.uploader_id, split_part(o.name, '/', 2)::uuid), o.name
    FROM storage.objects o LEFT JOIN public.shared_files f ON f.storage_path = o.name
    WHERE o.bucket_id = 'group-files' AND split_part(o.name, '/', 1) = OLD.id::text
      AND split_part(o.name, '/', 2) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ON CONFLICT (storage_path) DO NOTHING;
    RETURN OLD;
END;
$$;
CREATE TRIGGER queue_dissolved_group_objects BEFORE DELETE ON public.groups
FOR EACH ROW EXECUTE FUNCTION private.queue_dissolved_group_objects();
REVOKE ALL ON FUNCTION private.queue_deleted_group_file(), private.queue_dissolved_group_objects() FROM PUBLIC, anon, authenticated;

CREATE FUNCTION public.finalize_group_file(
    p_upload_id UUID, p_group_id UUID, p_file_name TEXT, p_mime_type TEXT,
    p_size_bytes BIGINT, p_storage_path TEXT, p_allow_downloads BOOLEAN
)
RETURNS SETOF public.shared_files
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_user UUID := auth.uid(); v_existing public.shared_files%ROWTYPE; v_metadata JSONB;
BEGIN
    IF v_user IS NULL THEN RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000'; END IF;
    -- Serialize finalization against group dissolution and deletion requests.
    PERFORM 1 FROM public.groups WHERE id = p_group_id FOR UPDATE;
    IF NOT FOUND OR NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    IF p_upload_id IS NULL OR p_storage_path IS DISTINCT FROM p_group_id::text || '/' || v_user::text || '/' || p_upload_id::text
       OR p_file_name IS NULL OR char_length(btrim(p_file_name)) NOT BETWEEN 1 AND 255
       OR p_mime_type IS NULL OR char_length(p_mime_type) NOT BETWEEN 1 AND 255
       OR p_size_bytes IS NULL OR p_size_bytes NOT BETWEEN 1 AND 20971520 OR p_allow_downloads IS NULL THEN
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
           OR v_existing.allow_downloads <> p_allow_downloads THEN
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
    RETURN QUERY INSERT INTO public.shared_files (id, group_id, uploader_id, file_name, mime_type, size_bytes, storage_path, allow_downloads)
    VALUES (p_upload_id, p_group_id, v_user, p_file_name, p_mime_type, p_size_bytes, p_storage_path, p_allow_downloads)
    RETURNING *;
END;
$$;
REVOKE ALL ON FUNCTION public.finalize_group_file(UUID, UUID, TEXT, TEXT, BIGINT, TEXT, BOOLEAN) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.finalize_group_file(UUID, UUID, TEXT, TEXT, BIGINT, TEXT, BOOLEAN) TO authenticated;

CREATE FUNCTION public.prepare_group_file_deletion(p_file_id UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_file public.shared_files%ROWTYPE; v_job public.group_file_cleanup_jobs%ROWTYPE; v_group UUID;
BEGIN
    IF auth.uid() IS NULL THEN RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000'; END IF;
    SELECT group_id INTO v_group FROM public.shared_files WHERE id = p_file_id;
    IF NOT FOUND THEN SELECT group_id INTO v_group FROM public.group_file_cleanup_jobs WHERE file_id = p_file_id; END IF;
    PERFORM 1 FROM public.groups WHERE id = v_group FOR UPDATE;
    IF NOT FOUND OR NOT private.is_group_member(v_group) THEN RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501'; END IF;
    SELECT * INTO v_file FROM public.shared_files WHERE id = p_file_id;
    IF FOUND THEN
        IF v_file.uploader_id <> auth.uid() THEN RAISE EXCEPTION 'not_the_uploader' USING ERRCODE = '42501'; END IF;
        INSERT INTO public.group_file_cleanup_jobs (file_id, group_id, uploader_id, storage_path)
        VALUES (v_file.id, v_file.group_id, v_file.uploader_id, v_file.storage_path)
        ON CONFLICT (storage_path) DO NOTHING;
    END IF;
    SELECT * INTO v_job FROM public.group_file_cleanup_jobs WHERE file_id = p_file_id;
    IF NOT FOUND OR v_job.uploader_id <> auth.uid() THEN RAISE EXCEPTION 'not_the_uploader' USING ERRCODE = '42501'; END IF;
    RETURN jsonb_build_object('id', v_job.id, 'storage_path', v_job.storage_path, 'state', v_job.state);
END;
$$;
REVOKE ALL ON FUNCTION public.prepare_group_file_deletion(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.prepare_group_file_deletion(UUID) TO authenticated;

-- The worker calls this only after Storage API deletion succeeds. Lost responses are safe.
CREATE FUNCTION public.finish_group_file_cleanup(p_job_id UUID, p_error TEXT DEFAULT NULL)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_job public.group_file_cleanup_jobs%ROWTYPE;
BEGIN
    SELECT * INTO v_job FROM public.group_file_cleanup_jobs WHERE id = p_job_id FOR UPDATE;
    IF NOT FOUND OR v_job.state = 'completed' THEN RETURN; END IF;
    IF p_error IS NOT NULL THEN
        UPDATE public.group_file_cleanup_jobs SET attempts = attempts + 1, last_error = left(p_error, 100) WHERE id = p_job_id;
        RETURN;
    END IF;
    DELETE FROM public.shared_files WHERE id = v_job.file_id AND storage_path = v_job.storage_path;
    UPDATE public.group_file_cleanup_jobs SET state = 'completed', attempts = attempts + 1,
        last_error = NULL, completed_at = now() WHERE id = p_job_id;
END;
$$;
REVOKE ALL ON FUNCTION public.finish_group_file_cleanup(UUID, TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.finish_group_file_cleanup(UUID, TEXT) TO service_role;
NOTIFY pgrst, 'reload schema';
