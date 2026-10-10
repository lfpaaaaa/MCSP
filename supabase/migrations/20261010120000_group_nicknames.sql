-- Group nicknames belong to a membership, never the global account profile.
ALTER TABLE public.memberships ADD COLUMN nickname TEXT
    CHECK (nickname IS NULL OR char_length(btrim(nickname)) BETWEEN 1 AND 40);

CREATE FUNCTION private.set_group_nickname(p_group_id UUID, p_nickname TEXT)
RETURNS TEXT LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user UUID := auth.uid();
    v_name TEXT := btrim(p_nickname, E' \t\n\r');
BEGIN
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '42501';
    END IF;
    IF v_name IS NULL OR char_length(v_name) NOT BETWEEN 1 AND 40 THEN
        RAISE EXCEPTION 'invalid_nickname' USING ERRCODE = '22023';
    END IF;
    UPDATE public.memberships SET nickname = v_name
    WHERE group_id = p_group_id AND user_id = v_user;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    RETURN v_name;
END;
$$;

CREATE FUNCTION public.set_group_nickname(p_group_id UUID, p_nickname TEXT)
RETURNS TEXT LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
    SELECT private.set_group_nickname(p_group_id, p_nickname);
$$;
REVOKE ALL ON FUNCTION private.set_group_nickname(UUID, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.set_group_nickname(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.set_group_nickname(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.set_group_nickname(UUID, TEXT) TO authenticated;

CREATE OR REPLACE FUNCTION private.group_members(p_group_id UUID)
RETURNS TABLE (
    user_id UUID,
    display_name TEXT,
    avatar_url TEXT,
    role TEXT,
    joined_at TIMESTAMPTZ
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

    -- Only names and avatars are shared with other members, never email addresses.
    RETURN QUERY
    SELECT m.user_id, coalesce(m.nickname, p.display_name, 'Member'), p.avatar_url, m.role, m.joined_at
    FROM public.memberships AS m
    LEFT JOIN public.profiles AS p ON p.id = m.user_id
    WHERE m.group_id = p_group_id
    ORDER BY (m.role = 'owner') DESC, coalesce(m.nickname, p.display_name, 'Member');
END;
$$;

CREATE OR REPLACE FUNCTION private.group_messages_before(
    p_group_id UUID,
    p_before_created_at TIMESTAMPTZ,
    p_before_id UUID,
    p_limit INTEGER
)
RETURNS TABLE (
    id UUID,
    group_id UUID,
    sender_id UUID,
    client_id UUID,
    body TEXT,
    created_at TIMESTAMPTZ,
    sender_name TEXT
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

    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'invalid_page_size' USING ERRCODE = '22023';
    END IF;

    IF (p_before_created_at IS NULL) <> (p_before_id IS NULL) THEN
        RAISE EXCEPTION 'invalid_cursor' USING ERRCODE = '22023';
    END IF;

    -- Only names are shared with other members, never email addresses.
    RETURN QUERY
    SELECT m.id, m.group_id, m.sender_id, m.client_id, m.body, m.created_at,
           coalesce(gm.nickname, p.display_name, 'Member')
    FROM public.messages AS m
    LEFT JOIN public.memberships AS gm ON gm.group_id = m.group_id AND gm.user_id = m.sender_id
    LEFT JOIN public.profiles AS p ON p.id = m.sender_id
    WHERE m.group_id = p_group_id
      AND (p_before_created_at IS NULL OR (m.created_at, m.id) < (p_before_created_at, p_before_id))
    ORDER BY m.created_at DESC, m.id DESC
    LIMIT p_limit;
END;
$$;

CREATE OR REPLACE FUNCTION private.group_messages_after(
    p_group_id UUID,
    p_after_created_at TIMESTAMPTZ,
    p_after_id UUID,
    p_limit INTEGER
)
RETURNS TABLE (
    id UUID,
    group_id UUID,
    sender_id UUID,
    client_id UUID,
    body TEXT,
    created_at TIMESTAMPTZ,
    sender_name TEXT
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

    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'invalid_page_size' USING ERRCODE = '22023';
    END IF;

    IF p_after_created_at IS NULL OR p_after_id IS NULL THEN
        RAISE EXCEPTION 'invalid_cursor' USING ERRCODE = '22023';
    END IF;

    RETURN QUERY
    SELECT m.id, m.group_id, m.sender_id, m.client_id, m.body, m.created_at,
           coalesce(gm.nickname, p.display_name, 'Member')
    FROM public.messages AS m
    LEFT JOIN public.memberships AS gm ON gm.group_id = m.group_id AND gm.user_id = m.sender_id
    LEFT JOIN public.profiles AS p ON p.id = m.sender_id
    WHERE m.group_id = p_group_id
      AND (m.created_at, m.id) > (p_after_created_at, p_after_id)
    ORDER BY m.created_at, m.id
    LIMIT p_limit;
END;
$$;

CREATE OR REPLACE FUNCTION private.group_files(p_group_id UUID)
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
    SELECT f.id, f.group_id, f.uploader_id, coalesce(gm.nickname, p.display_name, 'Member'), f.file_name,
           f.mime_type, f.size_bytes, f.storage_path, f.is_private, f.created_at
    FROM public.shared_files AS f
    LEFT JOIN public.memberships AS gm ON gm.group_id = f.group_id AND gm.user_id = f.uploader_id
    LEFT JOIN public.profiles AS p ON p.id = f.uploader_id
    WHERE f.group_id = p_group_id
    ORDER BY f.created_at DESC, f.id DESC;
END;
$$;

-- Existing SELECT RLS restricts nickname updates to the group's members.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime')
       AND NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime'
                       AND schemaname = 'public' AND tablename = 'memberships') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.memberships;
    END IF;
END;
$$;
