-- Only an owner can dissolve a group. Related memberships, messages, invitations and
-- shared-file metadata are removed by existing foreign-key cascades.
CREATE FUNCTION public.dissolve_group(p_group_id UUID)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
BEGIN
    IF auth.uid() IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;
    -- Lock the membership so an ownership change cannot race the permission check.
    PERFORM 1 FROM public.memberships
    WHERE group_id = p_group_id AND user_id = auth.uid() AND role = 'owner'
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'not_an_owner' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.groups WHERE id = p_group_id;
END;
$$;
REVOKE ALL ON FUNCTION public.dissolve_group(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.dissolve_group(UUID) TO authenticated;
NOTIFY pgrst, 'reload schema';
