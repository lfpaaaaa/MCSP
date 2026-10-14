-- Durable jobs: audio is already stored as a normal group attachment.
CREATE TABLE public.file_transcripts (
    file_id UUID PRIMARY KEY REFERENCES public.shared_files(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'queued' CHECK (status IN ('queued','processing','ready','failed')),
    transcript TEXT CHECK (length(transcript) <= 200000),
    language TEXT,
    error_code TEXT,
    lease_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.file_transcripts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.file_transcripts FROM anon, authenticated;
GRANT ALL ON public.file_transcripts TO service_role;

CREATE FUNCTION public.file_transcript(p_file_id UUID)
RETURNS SETOF public.file_transcripts
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.shared_files f WHERE f.id = p_file_id AND private.is_group_member(f.group_id)) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    RETURN QUERY SELECT * FROM public.file_transcripts WHERE file_id = p_file_id;
END;
$$;
CREATE FUNCTION public.request_file_transcript(p_file_id UUID)
RETURNS SETOF public.file_transcripts
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE f public.shared_files%ROWTYPE;
BEGIN
    SELECT * INTO f FROM public.shared_files WHERE id = p_file_id FOR UPDATE;
    IF NOT FOUND OR f.uploader_id IS DISTINCT FROM auth.uid() OR NOT private.is_group_member(f.group_id) THEN
        RAISE EXCEPTION 'not_file_uploader' USING ERRCODE = '42501';
    END IF;
    IF f.mime_type NOT LIKE 'audio/%' THEN
        RAISE EXCEPTION 'audio_required' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.file_transcripts(file_id) VALUES(p_file_id)
    ON CONFLICT(file_id) DO UPDATE SET status = 'queued', error_code = NULL, lease_id = NULL, updated_at = now(), requested_at = now()
        WHERE file_transcripts.status = 'failed';
    RETURN QUERY SELECT * FROM public.file_transcripts WHERE file_id = p_file_id;
END;
$$;

-- Only the trusted worker can lease and complete jobs. Expired leases recover after a crash.
CREATE FUNCTION public.claim_file_transcript()
RETURNS TABLE(file_id UUID, storage_path TEXT, mime_type TEXT, size_bytes BIGINT, lease_id UUID)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE job UUID; lease UUID := gen_random_uuid();
BEGIN
    SELECT t.file_id INTO job FROM public.file_transcripts t
    WHERE t.status = 'queued' OR (t.status = 'processing' AND t.updated_at < now() - interval '30 minutes')
    ORDER BY t.requested_at LIMIT 1 FOR UPDATE SKIP LOCKED;
    IF job IS NULL THEN RETURN; END IF;
    UPDATE public.file_transcripts SET status = 'processing', lease_id = lease, updated_at = now() WHERE file_transcripts.file_id = job;
    RETURN QUERY SELECT f.id, f.storage_path, f.mime_type, f.size_bytes, lease FROM public.shared_files f WHERE f.id = job;
END;
$$;
CREATE FUNCTION public.finish_file_transcript(p_file_id UUID, p_lease_id UUID, p_transcript TEXT, p_language TEXT, p_error_code TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE changed INTEGER;
BEGIN
    UPDATE public.file_transcripts SET status = CASE WHEN p_error_code IS NULL THEN 'ready' ELSE 'failed' END,
        transcript = CASE WHEN p_error_code IS NULL THEN left(coalesce(p_transcript,''),200000) ELSE NULL END,
        language = left(p_language,20), error_code = left(p_error_code,80), updated_at = now(), lease_id = NULL
    WHERE file_id = p_file_id AND status = 'processing' AND lease_id = p_lease_id;
    GET DIAGNOSTICS changed = ROW_COUNT;
    RETURN changed = 1;
END;
$$;
REVOKE ALL ON FUNCTION public.file_transcript(UUID), public.request_file_transcript(UUID), public.claim_file_transcript(), public.finish_file_transcript(UUID,UUID,TEXT,TEXT,TEXT) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.file_transcript(UUID), public.request_file_transcript(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.claim_file_transcript(), public.finish_file_transcript(UUID,UUID,TEXT,TEXT,TEXT) TO service_role;
