CREATE FUNCTION public.transfer_group_and_leave(p_group_id UUID, p_new_owner_id UUID)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_user UUID := auth.uid();
BEGIN
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;
    -- Serialize owner transfer and dissolution before locking memberships.
    PERFORM 1 FROM public.groups WHERE id = p_group_id FOR UPDATE;
    IF NOT EXISTS (SELECT 1 FROM public.memberships WHERE group_id=p_group_id AND user_id=v_user AND role='owner') THEN
        RAISE EXCEPTION 'not_an_owner' USING ERRCODE = '42501';
    END IF;
    IF p_new_owner_id IS NULL OR p_new_owner_id=v_user THEN
        RAISE EXCEPTION 'Choose another group member' USING ERRCODE = '22023';
    END IF;
    PERFORM 1 FROM public.memberships WHERE group_id=p_group_id AND user_id=p_new_owner_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'not_a_member' USING ERRCODE = '42501';
    END IF;
    UPDATE public.memberships SET role='owner' WHERE group_id=p_group_id AND user_id=p_new_owner_id;
    DELETE FROM public.memberships WHERE group_id=p_group_id AND user_id=v_user;
END;
$$;
REVOKE ALL ON FUNCTION public.transfer_group_and_leave(UUID,UUID) FROM PUBLIC,anon;
GRANT EXECUTE ON FUNCTION public.transfer_group_and_leave(UUID,UUID) TO authenticated;
-- An owner must use the atomic transfer operation or dissolve the group, not leave it ownerless.
ALTER POLICY "Users can leave a group" ON public.memberships
    USING ((SELECT auth.uid()) = user_id AND role <> 'owner');
CREATE OR REPLACE FUNCTION public.dissolve_group(p_group_id UUID)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;
    PERFORM 1 FROM public.groups WHERE id=p_group_id FOR UPDATE;
    PERFORM 1 FROM public.memberships WHERE group_id=p_group_id AND user_id=auth.uid() AND role='owner' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'not_an_owner' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.groups WHERE id=p_group_id;
END;
$$;
NOTIFY pgrst, 'reload schema';
