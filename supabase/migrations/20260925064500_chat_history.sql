-- Chat history for the app's offline cache. Pages use keyset pagination over (created_at, id), so a
-- page stays stable while new messages arrive, and every row carries the sender's display name,
-- which members cannot read from profiles directly.

-- ---------------------------------------------------------------------------
-- Privileged reads (private, not exposed through the Data API)
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.group_messages_before(
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
           coalesce(p.display_name, 'Member')
    FROM public.messages AS m
    LEFT JOIN public.profiles AS p ON p.id = m.sender_id
    WHERE m.group_id = p_group_id
      AND (p_before_created_at IS NULL OR (m.created_at, m.id) < (p_before_created_at, p_before_id))
    ORDER BY m.created_at DESC, m.id DESC
    LIMIT p_limit;
END;
$$;

CREATE FUNCTION private.group_messages_after(
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
           coalesce(p.display_name, 'Member')
    FROM public.messages AS m
    LEFT JOIN public.profiles AS p ON p.id = m.sender_id
    WHERE m.group_id = p_group_id
      AND (m.created_at, m.id) > (p_after_created_at, p_after_id)
    ORDER BY m.created_at, m.id
    LIMIT p_limit;
END;
$$;

-- ---------------------------------------------------------------------------
-- Data API (callable by signed-in users through supabase.postgrest.rpc)
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.group_messages_before(
    p_group_id UUID,
    p_before_created_at TIMESTAMPTZ DEFAULT NULL,
    p_before_id UUID DEFAULT NULL,
    p_limit INTEGER DEFAULT 30
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
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.group_messages_before(p_group_id, p_before_created_at, p_before_id, p_limit);
$$;

COMMENT ON FUNCTION public.group_messages_before(UUID, TIMESTAMPTZ, UUID, INTEGER) IS
    'Newest-first page of messages older than (p_before_created_at, p_before_id), or the latest page when both are null.';

CREATE FUNCTION public.group_messages_after(
    p_group_id UUID,
    p_after_created_at TIMESTAMPTZ,
    p_after_id UUID,
    p_limit INTEGER DEFAULT 100
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
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.group_messages_after(p_group_id, p_after_created_at, p_after_id, p_limit);
$$;

COMMENT ON FUNCTION public.group_messages_after(UUID, TIMESTAMPTZ, UUID, INTEGER) IS
    'Oldest-first messages newer than (p_after_created_at, p_after_id), used to catch up after reconnecting.';

-- ---------------------------------------------------------------------------
-- Function privileges
-- ---------------------------------------------------------------------------

REVOKE ALL ON FUNCTION private.group_messages_before(UUID, TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.group_messages_after(UUID, TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.group_messages_before(UUID, TIMESTAMPTZ, UUID, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION private.group_messages_after(UUID, TIMESTAMPTZ, UUID, INTEGER) TO authenticated;

REVOKE ALL ON FUNCTION public.group_messages_before(UUID, TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.group_messages_after(UUID, TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.group_messages_before(UUID, TIMESTAMPTZ, UUID, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.group_messages_after(UUID, TIMESTAMPTZ, UUID, INTEGER) TO authenticated;

-- The hosted platform's automatic-RLS event trigger function is also executable through PUBLIC.
-- Event triggers do not need that privilege, and API clients should not be able to call it.
DO $$
BEGIN
    IF to_regprocedure('public.rls_auto_enable()') IS NOT NULL THEN
        BEGIN
            REVOKE EXECUTE ON FUNCTION public.rls_auto_enable() FROM PUBLIC;
        EXCEPTION
            WHEN insufficient_privilege THEN
                RAISE NOTICE 'rls_auto_enable() is not owned by this role; privileges left unchanged';
        END;
    END IF;
END;
$$;
