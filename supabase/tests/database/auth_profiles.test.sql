BEGIN;

SELECT plan(8);

SELECT has_table(
    'public',
    'profiles',
    'profiles table exists'
);

SELECT ok(
    (SELECT relrowsecurity FROM pg_class WHERE oid = 'public.profiles'::regclass),
    'row level security is enabled on profiles'
);

INSERT INTO auth.users (
    id,
    email,
    raw_app_meta_data,
    raw_user_meta_data,
    created_at,
    updated_at
)
VALUES
    (
        '11111111-1111-1111-1111-111111111111',
        'student@example.com',
        '{"provider":"google","providers":["google"]}'::jsonb,
        '{"full_name":"Test Student","avatar_url":"https://example.com/avatar.png"}'::jsonb,
        now(),
        now()
    ),
    (
        '22222222-2222-2222-2222-222222222222',
        'other@example.com',
        '{"provider":"email","providers":["email"]}'::jsonb,
        '{"display_name":"Other Student"}'::jsonb,
        now(),
        now()
    );

SELECT is(
    (SELECT count(*) FROM public.profiles),
    2::bigint,
    'a profile is created for every auth user'
);

SELECT is(
    (SELECT display_name FROM public.profiles WHERE id = '11111111-1111-1111-1111-111111111111'),
    'Test Student',
    'OAuth display name is copied into the profile'
);

SELECT is(
    (SELECT auth_provider FROM public.profiles WHERE id = '11111111-1111-1111-1111-111111111111'),
    'google',
    'OAuth provider is copied into the profile'
);

SET LOCAL ROLE authenticated;
SELECT set_config(
    'request.jwt.claim.sub',
    '11111111-1111-1111-1111-111111111111',
    true
);

SELECT is(
    (SELECT count(*) FROM public.profiles),
    1::bigint,
    'an authenticated user can only read their own profile'
);

UPDATE public.profiles
SET display_name = 'Updated Student'
WHERE id = '11111111-1111-1111-1111-111111111111';

SELECT is(
    (SELECT display_name FROM public.profiles WHERE id = '11111111-1111-1111-1111-111111111111'),
    'Updated Student',
    'an authenticated user can update their own profile'
);

UPDATE public.profiles
SET display_name = 'Not Allowed'
WHERE id = '22222222-2222-2222-2222-222222222222';

RESET ROLE;

SELECT is(
    (SELECT display_name FROM public.profiles WHERE id = '22222222-2222-2222-2222-222222222222'),
    'Other Student',
    'an authenticated user cannot update another profile'
);

SELECT * FROM finish();
ROLLBACK;
