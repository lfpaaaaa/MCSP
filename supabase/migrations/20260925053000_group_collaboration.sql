-- Group collaboration: course groups, memberships, invite tokens, chat messages and shared-file
-- records. Users join a group only with a valid invite token, and row-level security limits all
-- group data to the group's members.

-- SECURITY DEFINER helpers live in a private schema that the Data API does not expose.
CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC;
GRANT USAGE ON SCHEMA private TO authenticated;

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

CREATE TABLE public.groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 60),
    course_code TEXT CHECK (course_code ~ '^[A-Z]{4}[0-9]{5}$'),
    private_content_enabled BOOLEAN NOT NULL DEFAULT false,
    created_by UUID REFERENCES auth.users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.groups IS
    'Course groups. Members join with invite tokens; the course code is an optional tag.';

CREATE INDEX groups_created_by_idx ON public.groups (created_by);

CREATE TABLE public.memberships (
    group_id UUID NOT NULL REFERENCES public.groups (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users (id) ON DELETE CASCADE,
    role TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('owner', 'member')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);

COMMENT ON COLUMN public.memberships.last_read_at IS
    'Messages from other members created after this time count as unread.';

CREATE INDEX memberships_user_id_idx ON public.memberships (user_id);

CREATE TABLE public.invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES public.groups (id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_by UUID REFERENCES auth.users (id) ON DELETE SET NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    max_uses INTEGER NOT NULL DEFAULT 20 CHECK (max_uses BETWEEN 1 AND 100),
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.invitations IS
    'Short-lived invite tokens shared by QR code and NFC. Only the SHA-256 hash of a token is stored.';

CREATE INDEX invitations_group_id_idx ON public.invitations (group_id);
CREATE INDEX invitations_created_by_idx ON public.invitations (created_by);

CREATE TABLE public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES public.groups (id) ON DELETE CASCADE,
    sender_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users (id) ON DELETE CASCADE,
    client_id UUID NOT NULL,
    body TEXT NOT NULL CHECK (char_length(btrim(body)) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT messages_sender_client_key UNIQUE (sender_id, client_id)
);

COMMENT ON COLUMN public.messages.client_id IS
    'Generated on the device so that a retried send is stored only once.';

CREATE INDEX messages_group_timeline_idx ON public.messages (group_id, created_at DESC, id DESC);

CREATE TABLE public.shared_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES public.groups (id) ON DELETE CASCADE,
    uploader_id UUID NOT NULL DEFAULT auth.uid() REFERENCES auth.users (id) ON DELETE CASCADE,
    file_name TEXT NOT NULL CHECK (char_length(file_name) BETWEEN 1 AND 255),
    mime_type TEXT NOT NULL CHECK (char_length(mime_type) BETWEEN 1 AND 255),
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    storage_path TEXT NOT NULL UNIQUE CHECK (split_part(storage_path, '/', 1) = group_id::TEXT),
    is_private BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN public.shared_files.storage_path IS
    'Object path in the group-files bucket; the first folder is always the group id.';

CREATE INDEX shared_files_group_timeline_idx ON public.shared_files (group_id, created_at DESC);
CREATE INDEX shared_files_uploader_id_idx ON public.shared_files (uploader_id);

-- ---------------------------------------------------------------------------
-- Membership check used by the row-level security policies
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.is_group_member(p_group_id UUID)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.memberships AS m
        WHERE m.group_id = p_group_id
          AND m.user_id = (SELECT auth.uid())
    );
$$;

COMMENT ON FUNCTION private.is_group_member(UUID) IS
    'True when the signed-in user belongs to the group. Runs as the owner so that the policies on memberships do not recurse.';

-- ---------------------------------------------------------------------------
-- Row-level security and privileges
-- ---------------------------------------------------------------------------

ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.shared_files ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE
    public.groups,
    public.memberships,
    public.invitations,
    public.messages,
    public.shared_files
FROM anon, authenticated;

-- Groups are created and joined only through the functions below, and invitations are never read
-- directly. Signed-in users therefore get no INSERT privilege on groups or memberships and no
-- privileges at all on invitations. Column-level INSERT grants stop clients from choosing the
-- sender, uploader or timestamps themselves.
GRANT SELECT ON TABLE public.groups TO authenticated;
GRANT SELECT, DELETE ON TABLE public.memberships TO authenticated;
GRANT UPDATE (last_read_at) ON TABLE public.memberships TO authenticated;
GRANT SELECT ON TABLE public.messages TO authenticated;
GRANT INSERT (group_id, client_id, body) ON TABLE public.messages TO authenticated;
GRANT SELECT, DELETE ON TABLE public.shared_files TO authenticated;
GRANT INSERT (group_id, file_name, mime_type, size_bytes, storage_path, is_private)
    ON TABLE public.shared_files TO authenticated;

CREATE POLICY "Members can read their groups"
    ON public.groups
    FOR SELECT
    TO authenticated
    USING (private.is_group_member(id));

CREATE POLICY "Members can read the memberships of their groups"
    ON public.memberships
    FOR SELECT
    TO authenticated
    USING (private.is_group_member(group_id));

CREATE POLICY "Users can update their own read marker"
    ON public.memberships
    FOR UPDATE
    TO authenticated
    USING ((SELECT auth.uid()) = user_id)
    WITH CHECK ((SELECT auth.uid()) = user_id);

CREATE POLICY "Users can leave a group"
    ON public.memberships
    FOR DELETE
    TO authenticated
    USING ((SELECT auth.uid()) = user_id);

CREATE POLICY "Members can read group messages"
    ON public.messages
    FOR SELECT
    TO authenticated
    USING (private.is_group_member(group_id));

CREATE POLICY "Members can send messages as themselves"
    ON public.messages
    FOR INSERT
    TO authenticated
    WITH CHECK ((SELECT auth.uid()) = sender_id AND private.is_group_member(group_id));

CREATE POLICY "Members can read shared file records"
    ON public.shared_files
    FOR SELECT
    TO authenticated
    USING (private.is_group_member(group_id));

CREATE POLICY "Members can add file records as themselves"
    ON public.shared_files
    FOR INSERT
    TO authenticated
    WITH CHECK ((SELECT auth.uid()) = uploader_id AND private.is_group_member(group_id));

CREATE POLICY "Uploaders can delete their file records"
    ON public.shared_files
    FOR DELETE
    TO authenticated
    USING ((SELECT auth.uid()) = uploader_id);

-- New chat messages and files are pushed to subscribed members.
ALTER PUBLICATION supabase_realtime ADD TABLE public.messages, public.shared_files;

-- ---------------------------------------------------------------------------
-- Privileged operations (private, not exposed through the Data API)
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.create_group(p_name TEXT, p_course_code TEXT)
RETURNS public.groups
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_user UUID := auth.uid();
    v_group public.groups;
BEGIN
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;

    INSERT INTO public.groups (name, course_code, created_by)
    VALUES (btrim(p_name), nullif(upper(btrim(coalesce(p_course_code, ''))), ''), v_user)
    RETURNING * INTO v_group;

    INSERT INTO public.memberships (group_id, user_id, role)
    VALUES (v_group.id, v_user, 'owner');

    RETURN v_group;
END;
$$;

CREATE FUNCTION private.create_group_invite(
    p_group_id UUID,
    p_ttl_minutes INTEGER,
    p_max_uses INTEGER
)
RETURNS TABLE (token TEXT, expires_at TIMESTAMPTZ)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
#variable_conflict use_column
DECLARE
    v_token TEXT;
    v_expires_at TIMESTAMPTZ;
BEGIN
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;

    IF p_ttl_minutes IS NULL OR p_ttl_minutes NOT BETWEEN 1 AND 1440
        OR p_max_uses IS NULL OR p_max_uses NOT BETWEEN 1 AND 100 THEN
        RAISE EXCEPTION 'invalid_invite_settings' USING ERRCODE = '22023';
    END IF;

    -- 64 hexadecimal characters built from two random UUIDs (244 random bits).
    v_token := encode(uuid_send(gen_random_uuid()), 'hex') || encode(uuid_send(gen_random_uuid()), 'hex');
    v_expires_at := now() + make_interval(mins => p_ttl_minutes);

    INSERT INTO public.invitations (group_id, token_hash, created_by, expires_at, max_uses)
    VALUES (
        p_group_id,
        encode(sha256(convert_to(v_token, 'UTF8')), 'hex'),
        auth.uid(),
        v_expires_at,
        p_max_uses
    );

    RETURN QUERY SELECT v_token, v_expires_at;
END;
$$;

CREATE FUNCTION private.join_group_with_token(p_token TEXT)
RETURNS public.groups
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_user UUID := auth.uid();
    v_invite public.invitations;
    v_group public.groups;
BEGIN
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;

    SELECT * INTO v_invite
    FROM public.invitations
    WHERE token_hash = encode(sha256(convert_to(btrim(coalesce(p_token, '')), 'UTF8')), 'hex')
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'invite_not_found' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_group FROM public.groups WHERE id = v_invite.group_id;

    -- Existing members succeed without using up the invite.
    IF EXISTS (
        SELECT 1
        FROM public.memberships
        WHERE group_id = v_invite.group_id
          AND user_id = v_user
    ) THEN
        RETURN v_group;
    END IF;

    IF v_invite.expires_at <= now() THEN
        RAISE EXCEPTION 'invite_expired' USING ERRCODE = '22023';
    END IF;

    IF v_invite.use_count >= v_invite.max_uses THEN
        RAISE EXCEPTION 'invite_used_up' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.memberships (group_id, user_id, role)
    VALUES (v_invite.group_id, v_user, 'member');

    UPDATE public.invitations
    SET use_count = use_count + 1
    WHERE id = v_invite.id;

    RETURN v_group;
END;
$$;

CREATE FUNCTION private.group_members(p_group_id UUID)
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
    SELECT m.user_id, coalesce(p.display_name, 'Member'), p.avatar_url, m.role, m.joined_at
    FROM public.memberships AS m
    LEFT JOIN public.profiles AS p ON p.id = m.user_id
    WHERE m.group_id = p_group_id
    ORDER BY (m.role = 'owner') DESC, p.display_name;
END;
$$;

-- ---------------------------------------------------------------------------
-- Data API (callable by signed-in users through supabase.postgrest.rpc)
-- ---------------------------------------------------------------------------

CREATE FUNCTION public.create_group(p_name TEXT, p_course_code TEXT DEFAULT NULL)
RETURNS public.groups
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.create_group(p_name, p_course_code);
$$;

COMMENT ON FUNCTION public.create_group(TEXT, TEXT) IS
    'Creates a group with the signed-in user as its owner.';

CREATE FUNCTION public.create_group_invite(
    p_group_id UUID,
    p_ttl_minutes INTEGER DEFAULT 10,
    p_max_uses INTEGER DEFAULT 20
)
RETURNS TABLE (token TEXT, expires_at TIMESTAMPTZ)
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.create_group_invite(p_group_id, p_ttl_minutes, p_max_uses);
$$;

COMMENT ON FUNCTION public.create_group_invite(UUID, INTEGER, INTEGER) IS
    'Issues a short-lived invite token for a group the signed-in user belongs to. The token is returned once and stored only as a hash.';

CREATE FUNCTION public.join_group_with_token(p_token TEXT)
RETURNS public.groups
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.join_group_with_token(p_token);
$$;

COMMENT ON FUNCTION public.join_group_with_token(TEXT) IS
    'Joins the group behind an invite token. Fails with invite_not_found, invite_expired or invite_used_up.';

CREATE FUNCTION public.group_members(p_group_id UUID)
RETURNS TABLE (
    user_id UUID,
    display_name TEXT,
    avatar_url TEXT,
    role TEXT,
    joined_at TIMESTAMPTZ
)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT * FROM private.group_members(p_group_id);
$$;

COMMENT ON FUNCTION public.group_members(UUID) IS
    'Names and avatars of the members of a group the signed-in user belongs to.';

CREATE FUNCTION public.mark_group_read(p_group_id UUID)
RETURNS VOID
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    UPDATE public.memberships
    SET last_read_at = now()
    WHERE group_id = p_group_id
      AND user_id = (SELECT auth.uid());
$$;

CREATE FUNCTION public.my_group_summaries()
RETURNS TABLE (
    id UUID,
    name TEXT,
    course_code TEXT,
    private_content_enabled BOOLEAN,
    created_by UUID,
    created_at TIMESTAMPTZ,
    my_role TEXT,
    member_count INTEGER,
    unread_count INTEGER,
    latest_message_preview TEXT,
    latest_activity_at TIMESTAMPTZ,
    latest_file_name TEXT
)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT
        g.id,
        g.name,
        g.course_code,
        g.private_content_enabled,
        g.created_by,
        g.created_at,
        me.role,
        (SELECT count(*)::INTEGER FROM public.memberships AS m WHERE m.group_id = g.id),
        (
            SELECT count(*)::INTEGER
            FROM public.messages AS msg
            WHERE msg.group_id = g.id
              AND msg.created_at > me.last_read_at
              AND msg.sender_id <> me.user_id
        ),
        latest_message.body,
        greatest(latest_message.created_at, latest_file.created_at, g.created_at),
        latest_file.file_name
    FROM public.memberships AS me
    JOIN public.groups AS g ON g.id = me.group_id
    LEFT JOIN LATERAL (
        SELECT msg.body, msg.created_at
        FROM public.messages AS msg
        WHERE msg.group_id = g.id
        ORDER BY msg.created_at DESC, msg.id DESC
        LIMIT 1
    ) AS latest_message ON true
    LEFT JOIN LATERAL (
        SELECT f.file_name, f.created_at
        FROM public.shared_files AS f
        WHERE f.group_id = g.id
        ORDER BY f.created_at DESC
        LIMIT 1
    ) AS latest_file ON true
    WHERE me.user_id = (SELECT auth.uid())
    ORDER BY 11 DESC;
$$;

COMMENT ON FUNCTION public.my_group_summaries() IS
    'Groups of the signed-in user with member counts, unread counts and latest activity.';

-- ---------------------------------------------------------------------------
-- Function privileges
-- ---------------------------------------------------------------------------

REVOKE ALL ON FUNCTION private.is_group_member(UUID) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.create_group(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.create_group_invite(UUID, INTEGER, INTEGER) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.join_group_with_token(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION private.group_members(UUID) FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION private.is_group_member(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION private.create_group(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION private.create_group_invite(UUID, INTEGER, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION private.join_group_with_token(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION private.group_members(UUID) TO authenticated;

REVOKE ALL ON FUNCTION public.create_group(TEXT, TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.create_group_invite(UUID, INTEGER, INTEGER) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.join_group_with_token(TEXT) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.group_members(UUID) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.mark_group_read(UUID) FROM PUBLIC, anon;
REVOKE ALL ON FUNCTION public.my_group_summaries() FROM PUBLIC, anon;

GRANT EXECUTE ON FUNCTION public.create_group(TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.create_group_invite(UUID, INTEGER, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.join_group_with_token(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.group_members(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.mark_group_read(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.my_group_summaries() TO authenticated;

-- Trigger functions are not meant to be called through the Data API.
REVOKE EXECUTE ON FUNCTION public.sync_auth_user_profile() FROM anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.set_profile_updated_at() FROM anon, authenticated;

DO $$
BEGIN
    -- Created by the hosted platform for automatic RLS; it does not exist in local stacks.
    IF to_regprocedure('public.rls_auto_enable()') IS NOT NULL THEN
        BEGIN
            REVOKE EXECUTE ON FUNCTION public.rls_auto_enable() FROM anon, authenticated;
        EXCEPTION
            WHEN insufficient_privilege THEN
                RAISE NOTICE 'rls_auto_enable() is not owned by this role; privileges left unchanged';
        END;
    END IF;
END;
$$;
