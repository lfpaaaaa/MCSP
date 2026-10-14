ALTER TABLE public.file_transcripts
    ADD COLUMN revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    ADD COLUMN edited_at TIMESTAMPTZ;

-- Members may read; only the recording's uploader can change a completed result.
CREATE FUNCTION public.update_file_transcript(p_file_id UUID, p_transcript TEXT, p_expected_revision INTEGER)
RETURNS SETOF public.file_transcripts
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE f public.shared_files%ROWTYPE; current_transcript public.file_transcripts%ROWTYPE;
BEGIN
    SELECT * INTO f FROM public.shared_files WHERE id = p_file_id FOR UPDATE;
    IF NOT FOUND OR f.uploader_id IS DISTINCT FROM auth.uid() OR NOT private.is_group_member(f.group_id) THEN
        RAISE EXCEPTION 'not_file_uploader' USING ERRCODE = '42501';
    END IF;
    IF p_transcript IS NULL OR length(p_transcript) > 200000 OR p_expected_revision IS NULL OR p_expected_revision < 0 THEN
        RAISE EXCEPTION 'invalid_transcript_edit' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO current_transcript FROM public.file_transcripts WHERE file_id = p_file_id FOR UPDATE;
    IF NOT FOUND OR current_transcript.status <> 'ready' THEN
        RAISE EXCEPTION 'transcript_not_ready' USING ERRCODE = '22023';
    END IF;
    -- A lost response can be retried without overwriting a newer, different draft.
    IF current_transcript.transcript IS NOT DISTINCT FROM p_transcript THEN
        RETURN NEXT current_transcript;
        RETURN;
    END IF;
    IF current_transcript.revision <> p_expected_revision THEN
        RAISE EXCEPTION 'transcript_conflict' USING ERRCODE = '40001';
    END IF;
    RETURN QUERY UPDATE public.file_transcripts SET transcript = p_transcript,
        revision = revision + 1, edited_at = clock_timestamp(), updated_at = clock_timestamp()
        WHERE file_id = p_file_id RETURNING *;
END;
$$;
REVOKE ALL ON FUNCTION public.update_file_transcript(UUID,TEXT,INTEGER) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.update_file_transcript(UUID,TEXT,INTEGER) TO authenticated;
