-- Avatar references in Auth metadata and profiles use storage://profile-avatars/<user>/<uuid>.jpg.
-- Store only compact JPEGs. Signed-in owners and current group peers can read; only owners write.
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('profile-avatars', 'profile-avatars', false, 1048576, ARRAY['image/jpeg'])
ON CONFLICT (id) DO UPDATE SET public = false, file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

CREATE FUNCTION private.can_read_profile_avatar(p_name TEXT)
RETURNS BOOLEAN LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
    SELECT (SELECT auth.uid())::TEXT = split_part(p_name, '/', 1)
        OR EXISTS (
            SELECT 1 FROM public.profiles AS p
            JOIN public.memberships AS owner_membership ON owner_membership.user_id = p.id
            JOIN public.memberships AS viewer_membership ON viewer_membership.group_id = owner_membership.group_id
            WHERE p.id::TEXT = split_part(p_name, '/', 1)
              AND p.avatar_url = 'storage://profile-avatars/' || p_name
              AND viewer_membership.user_id = (SELECT auth.uid())
        );
$$;
REVOKE ALL ON FUNCTION private.can_read_profile_avatar(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.can_read_profile_avatar(TEXT) TO authenticated;

CREATE POLICY "Owners and current group peers can read profile avatars"
    ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'profile-avatars' AND private.can_read_profile_avatar(name));
CREATE POLICY "Users can upload their own profile avatars"
    ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'profile-avatars' AND split_part(name, '/', 1) = (SELECT auth.uid())::TEXT
        AND name ~ '^[0-9a-fA-F-]{36}/[0-9a-fA-F-]{36}\.jpg$');
CREATE POLICY "Users can delete their own profile avatars"
    ON storage.objects FOR DELETE TO authenticated
    USING (bucket_id = 'profile-avatars' AND split_part(name, '/', 1) = (SELECT auth.uid())::TEXT);
