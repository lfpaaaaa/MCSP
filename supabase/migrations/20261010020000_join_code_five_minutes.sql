-- Typed codes expire at the original creation time + 5 minutes, including existing groups.
-- Rotating a code cannot extend that deadline. QR/NFC token invites retain their own expiry.
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

        IF clock_timestamp() >= v_group.created_at + interval '5 minutes' THEN
            RAISE EXCEPTION 'join_code_expired' USING ERRCODE = '22023';
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

CREATE OR REPLACE FUNCTION private.reset_group_code(p_group_id UUID)
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

    IF EXISTS (SELECT 1 FROM public.groups WHERE id=p_group_id
        AND clock_timestamp() >= created_at + interval '5 minutes') THEN
        RAISE EXCEPTION 'join_code_expired' USING ERRCODE = '22023';
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

COMMENT ON COLUMN public.groups.join_code IS 'Typed code valid only for five minutes after group creation. Use token invitations afterwards.';
NOTIFY pgrst, 'reload schema';
