-- Six-character group codes that people type to join a group. Every group gets one when it is
-- created and keeps it until an owner resets it; members see it on the group screen. The QR and
-- NFC invite tokens stay as they are, and join_group_with_token accepts both.
--
-- Codes use the 32 letters and digits that are hard to confuse (no 0/O/1/I), so there are about
-- a billion of them. Only signed-in users can try codes, and a wrong code costs a one-second
-- wait, which makes guessing impractical. Only members can read a group's code.

-- Housekeeping: the push migration created pg_net in the public schema, which the security
-- linter flags; extensions belong in the extensions schema. Nothing depends on it at rest, so
-- it can be moved by recreating it.
DROP EXTENSION IF EXISTS pg_net;
CREATE EXTENSION IF NOT EXISTS pg_net WITH SCHEMA extensions;

CREATE FUNCTION private.random_join_code()
RETURNS TEXT
LANGUAGE sql
VOLATILE
SET search_path = ''
AS $$
    -- The first six bytes of a random UUID are fully random; each picks one of 32 characters.
    SELECT string_agg(
        substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + (get_byte(uuid_send(gen_random_uuid()), i) % 32), 1),
        ''
    )
    FROM generate_series(0, 5) AS i;
$$;

REVOKE ALL ON FUNCTION private.random_join_code() FROM PUBLIC, anon, authenticated;

ALTER TABLE public.groups
    ADD COLUMN join_code TEXT NOT NULL DEFAULT private.random_join_code()
        CHECK (join_code ~ '^[A-HJ-NP-Z2-9]{6}$');

ALTER TABLE public.groups ADD CONSTRAINT groups_join_code_key UNIQUE (join_code);

COMMENT ON COLUMN public.groups.join_code IS
    'Six-character code members share so that others can join by typing it. Reset by an owner with reset_group_code.';

-- ---------------------------------------------------------------------------
-- Joining with a code or a token
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION private.join_group_with_token(p_token TEXT)
RETURNS public.groups
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_user UUID := auth.uid();
    v_input TEXT := btrim(coalesce(p_token, ''));
    v_code TEXT := upper(regexp_replace(v_input, '[\s-]', '', 'g'));
    v_invite public.invitations;
    v_group public.groups;
BEGIN
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;

    -- A six-character code typed by hand; spaces, dashes and case do not matter.
    IF v_code ~ '^[A-Z0-9]{6}$' THEN
        SELECT * INTO v_group FROM public.groups WHERE join_code = v_code;
        IF NOT FOUND THEN
            -- Slows down guessing without affecting someone who mistyped once.
            PERFORM pg_sleep(1);
            RAISE EXCEPTION 'invite_not_found' USING ERRCODE = '22023';
        END IF;

        INSERT INTO public.memberships (group_id, user_id, role)
        VALUES (v_group.id, v_user, 'member')
        ON CONFLICT (group_id, user_id) DO NOTHING;

        RETURN v_group;
    END IF;

    -- Otherwise an invite token from a QR code or an NFC tag.
    SELECT * INTO v_invite
    FROM public.invitations
    WHERE token_hash = encode(sha256(convert_to(v_input, 'UTF8')), 'hex')
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

COMMENT ON FUNCTION public.join_group_with_token(TEXT) IS
    'Joins the group behind a six-character group code or an invite token. Fails with invite_not_found, invite_expired or invite_used_up.';

-- ---------------------------------------------------------------------------
-- Resetting a code
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.reset_group_code(p_group_id UUID)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    v_code TEXT;
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM public.memberships
        WHERE group_id = p_group_id
          AND user_id = (SELECT auth.uid())
          AND role = 'owner'
    ) THEN
        RAISE EXCEPTION 'not_an_owner' USING ERRCODE = '42501';
    END IF;

    -- A fresh code; the one-in-a-billion clash with another group is tried again.
    FOR attempt IN 1..5 LOOP
        v_code := private.random_join_code();
        BEGIN
            UPDATE public.groups SET join_code = v_code WHERE id = p_group_id;
            RETURN v_code;
        EXCEPTION WHEN unique_violation THEN
            NULL;
        END;
    END LOOP;
    RAISE EXCEPTION 'join_code_unavailable' USING ERRCODE = '53400';
END;
$$;

CREATE FUNCTION public.reset_group_code(p_group_id UUID)
RETURNS TEXT
LANGUAGE sql
SECURITY INVOKER
SET search_path = ''
AS $$
    SELECT private.reset_group_code(p_group_id);
$$;

COMMENT ON FUNCTION public.reset_group_code(UUID) IS
    'Replaces the group''s join code; the old one stops working. Owners only.';

-- ---------------------------------------------------------------------------
-- Group summaries carry the code, so the group list can show it
-- ---------------------------------------------------------------------------

DROP FUNCTION public.my_group_summaries();

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
    latest_file_name TEXT,
    join_code TEXT
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
        latest_file.file_name,
        g.join_code
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
    'Groups of the signed-in user with member counts, unread counts, latest activity and the join code.';

-- ---------------------------------------------------------------------------
-- Privileges
-- ---------------------------------------------------------------------------

REVOKE ALL ON FUNCTION private.reset_group_code(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.reset_group_code(UUID) TO authenticated;

REVOKE ALL ON FUNCTION public.reset_group_code(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.reset_group_code(UUID) TO authenticated;

REVOKE ALL ON FUNCTION public.my_group_summaries() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.my_group_summaries() TO authenticated;
