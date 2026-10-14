-- Personal pins are private to their creator. A group has at most one active shared pin,
-- managed by its current owner. UPDATE-based unpin supports filtered, RLS-protected Realtime.
CREATE TABLE public.group_pins (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES public.groups(id) ON DELETE CASCADE,
    message_id UUID REFERENCES public.messages(id) ON DELETE CASCADE,
    file_id UUID REFERENCES public.shared_files(id) ON DELETE CASCADE,
    visibility TEXT NOT NULL CHECK (visibility IN ('personal','group')),
    pinned_by UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    pinned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    active BOOLEAN NOT NULL DEFAULT true,
    CHECK (num_nonnulls(message_id,file_id) = 1),
    UNIQUE(group_id,visibility,pinned_by,message_id),
    UNIQUE(group_id,visibility,pinned_by,file_id)
);
CREATE UNIQUE INDEX group_pins_one_shared_idx ON public.group_pins(group_id) WHERE active AND visibility='group';
CREATE INDEX group_pins_active_idx ON public.group_pins(group_id,pinned_at DESC) WHERE active;
ALTER TABLE public.group_pins ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.group_pins FROM anon, authenticated;
GRANT SELECT ON public.group_pins TO authenticated;
GRANT ALL ON public.group_pins TO service_role;
CREATE POLICY "Members can read shared pins and their own pins" ON public.group_pins FOR SELECT TO authenticated
    USING (private.is_group_member(group_id) AND (visibility='group' OR pinned_by=(SELECT auth.uid())));
ALTER PUBLICATION supabase_realtime ADD TABLE public.group_pins;

CREATE FUNCTION public.group_pinned_items(p_group_id UUID)
RETURNS JSONB LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE is_owner BOOLEAN; items JSONB;
BEGIN
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE='42501';
    END IF;
    SELECT EXISTS(SELECT 1 FROM public.memberships WHERE group_id=p_group_id AND user_id=auth.uid() AND role='owner') INTO is_owner;
    SELECT coalesce(jsonb_agg(item ORDER BY (visibility='group') DESC,pinned_at DESC,id),'[]'::jsonb) INTO items
    FROM (
        SELECT pin.id,pin.visibility,pin.pinned_at,jsonb_build_object(
            'id',pin.id,'visibility',pin.visibility,
            'pinned_by_name',coalesce(pm.nickname,pp.display_name,'Member'),'pinned_at',pin.pinned_at,
            'can_unpin',CASE WHEN pin.visibility='group' THEN is_owner ELSE pin.pinned_by=auth.uid() END,
            'message',CASE WHEN m.id IS NOT NULL THEN to_jsonb(m) || jsonb_build_object('sender_name',coalesce(mm.nickname,mp.display_name,'Member')) END,
            'file',CASE WHEN f.id IS NOT NULL THEN to_jsonb(f) || jsonb_build_object('uploader_name',coalesce(fm.nickname,fp.display_name,'Member')) END
        ) AS item
        FROM public.group_pins pin
        LEFT JOIN public.memberships pm ON pm.group_id=pin.group_id AND pm.user_id=pin.pinned_by
        LEFT JOIN public.profiles pp ON pp.id=pin.pinned_by
        LEFT JOIN public.messages m ON m.id=pin.message_id AND m.group_id=pin.group_id
        LEFT JOIN public.memberships mm ON mm.group_id=pin.group_id AND mm.user_id=m.sender_id
        LEFT JOIN public.profiles mp ON mp.id=m.sender_id
        LEFT JOIN public.shared_files f ON f.id=pin.file_id AND f.group_id=pin.group_id
        LEFT JOIN public.memberships fm ON fm.group_id=pin.group_id AND fm.user_id=f.uploader_id
        LEFT JOIN public.profiles fp ON fp.id=f.uploader_id
        WHERE pin.group_id=p_group_id AND pin.active AND (pin.visibility='group' OR pin.pinned_by=auth.uid())
            AND (m.id IS NOT NULL OR f.id IS NOT NULL)
    ) visible;
    RETURN jsonb_build_object('can_pin_for_everyone',is_owner,'items',items);
END;
$$;

CREATE FUNCTION public.pin_group_item(p_group_id UUID,p_message_id UUID DEFAULT NULL,p_file_id UUID DEFAULT NULL,p_for_everyone BOOLEAN DEFAULT false)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE scope TEXT := CASE WHEN p_for_everyone THEN 'group' ELSE 'personal' END;
BEGIN
    -- Serialize shared replacement, owner transfer and dissolution; check the current role.
    PERFORM 1 FROM public.groups WHERE id=p_group_id FOR UPDATE;
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE='42501';
    END IF;
    IF p_for_everyone AND NOT EXISTS(SELECT 1 FROM public.memberships WHERE group_id=p_group_id AND user_id=auth.uid() AND role='owner') THEN
        RAISE EXCEPTION 'not_an_owner' USING ERRCODE='42501';
    END IF;
    IF num_nonnulls(p_message_id,p_file_id) <> 1 THEN
        RAISE EXCEPTION 'pin_target_invalid' USING ERRCODE='22023';
    END IF;
    IF p_message_id IS NOT NULL THEN
        PERFORM 1 FROM public.messages WHERE id=p_message_id AND group_id=p_group_id FOR KEY SHARE;
    ELSE
        PERFORM 1 FROM public.shared_files WHERE id=p_file_id AND group_id=p_group_id FOR KEY SHARE;
    END IF;
    IF NOT FOUND THEN RAISE EXCEPTION 'pin_target_missing' USING ERRCODE='P0002'; END IF;
    IF scope='group' THEN
        -- Lost-response retries do not reorder or change attribution of an existing shared pin.
        IF EXISTS(SELECT 1 FROM public.group_pins WHERE group_id=p_group_id AND visibility='group' AND active
            AND message_id IS NOT DISTINCT FROM p_message_id AND file_id IS NOT DISTINCT FROM p_file_id) THEN RETURN; END IF;
        UPDATE public.group_pins SET active=false WHERE group_id=p_group_id AND visibility='group' AND active;
    END IF;
    IF p_message_id IS NOT NULL THEN
        INSERT INTO public.group_pins(group_id,message_id,visibility,pinned_by) VALUES(p_group_id,p_message_id,scope,auth.uid())
        ON CONFLICT(group_id,visibility,pinned_by,message_id) DO UPDATE SET active=true,pinned_at=clock_timestamp()
            WHERE NOT group_pins.active;
    ELSE
        INSERT INTO public.group_pins(group_id,file_id,visibility,pinned_by) VALUES(p_group_id,p_file_id,scope,auth.uid())
        ON CONFLICT(group_id,visibility,pinned_by,file_id) DO UPDATE SET active=true,pinned_at=clock_timestamp()
            WHERE NOT group_pins.active;
    END IF;
END;
$$;

CREATE FUNCTION public.unpin_group_item(p_group_id UUID,p_pin_id UUID)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE pin public.group_pins%ROWTYPE;
BEGIN
    PERFORM 1 FROM public.groups WHERE id=p_group_id FOR UPDATE;
    IF NOT private.is_group_member(p_group_id) THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE='42501';
    END IF;
    SELECT * INTO pin FROM public.group_pins WHERE id=p_pin_id AND group_id=p_group_id FOR UPDATE;
    IF NOT FOUND THEN RETURN; END IF;
    IF (pin.visibility='personal' AND pin.pinned_by<>auth.uid()) OR
       (pin.visibility='group' AND NOT EXISTS(SELECT 1 FROM public.memberships WHERE group_id=p_group_id AND user_id=auth.uid() AND role='owner')) THEN
        RAISE EXCEPTION 'pin_remove_forbidden' USING ERRCODE='42501';
    END IF;
    UPDATE public.group_pins SET active=false WHERE id=pin.id AND active;
END;
$$;
REVOKE ALL ON FUNCTION public.group_pinned_items(UUID),public.pin_group_item(UUID,UUID,UUID,BOOLEAN),public.unpin_group_item(UUID,UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.group_pinned_items(UUID),public.pin_group_item(UUID,UUID,UUID,BOOLEAN),public.unpin_group_item(UUID,UUID) TO authenticated;
NOTIFY pgrst, 'reload schema';
