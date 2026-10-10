-- Use concise course-code names for existing and future small-class groups.
UPDATE public.groups
SET name = course_code || '-' || split_part(timetable_key, '|', 2)
WHERE timetable_key IS NOT NULL
  AND split_part(timetable_key, '|', 2) IN ('tutorial', 'workshop');

CREATE OR REPLACE FUNCTION private.sync_timetable_groups(p_groups JSONB)
RETURNS SETOF public.groups
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
    v_user UUID := auth.uid();
    v_spec JSONB;
    v_course TEXT;
    v_name TEXT;
    v_activity TEXT;
    v_location TEXT;
    v_day INTEGER;
    v_start TEXT;
    v_end TEXT;
    v_key TEXT;
    v_slot TEXT;
    v_group public.groups;
BEGIN
    IF v_user IS NULL THEN
        RAISE EXCEPTION 'not_authenticated' USING ERRCODE = '28000';
    END IF;
    IF p_groups IS NULL OR jsonb_typeof(p_groups) <> 'array' OR jsonb_array_length(p_groups) > 1000 THEN
        RAISE EXCEPTION 'invalid_timetable_groups' USING ERRCODE = '22023';
    END IF;
    -- Stable order also serializes concurrent imports without duplicate groups or memberships.
    FOR v_spec IN SELECT value FROM jsonb_array_elements(p_groups) ORDER BY value::text LOOP
        v_course := upper(btrim(v_spec->>'course_code'));
        v_name := btrim(v_spec->>'name');
        v_activity := lower(btrim(v_spec->>'activity'));
        IF v_course IS NULL OR v_course !~ '^[A-Z]{4}[0-9]{5}$'
            OR v_name IS NULL OR char_length(v_name) NOT BETWEEN 1 AND 60
            OR v_activity IS NULL OR v_activity NOT IN ('course', 'tutorial', 'workshop') THEN
            RAISE EXCEPTION 'invalid_timetable_groups' USING ERRCODE = '22023';
        END IF;
        v_key := v_course || '|course';
        v_slot := NULL;
        IF v_activity <> 'course' THEN
            v_name := v_course || '-' || v_activity;
            IF coalesce(v_spec->>'day', '') !~ '^[1-7]$' THEN
                RAISE EXCEPTION 'invalid_timetable_groups' USING ERRCODE = '22023';
            END IF;
            v_day := (v_spec->>'day')::integer;
            v_start := v_spec->>'start';
            v_end := v_spec->>'end';
            v_location := btrim(regexp_replace(regexp_replace(lower(normalize(v_spec->>'location', NFKC)), '\s+', ' ', 'g'), '\s*,\s*', ',', 'g'));
            IF v_start IS NULL OR v_start !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
                OR v_end IS NULL OR v_end !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
                OR v_location IS NULL OR char_length(v_location) NOT BETWEEN 1 AND 500 THEN
                RAISE EXCEPTION 'invalid_timetable_groups' USING ERRCODE = '22023';
            END IF;
            v_key := v_course || '|' || v_activity || '|' || v_day || '|' || v_start || '|' || v_end || '|' || v_location;
            v_slot := (ARRAY['Mon','Tue','Wed','Thu','Fri','Sat','Sun'])[v_day] || ' ' || v_start || '–' || v_end || ' · ' || v_location;
        END IF;
        -- System-managed groups have no student owner. A student cannot dissolve the class group.
        INSERT INTO public.groups(name, course_code, timetable_key, timetable_slot)
        VALUES(v_name, v_course, v_key, v_slot)
        ON CONFLICT (timetable_key) DO UPDATE SET timetable_key = excluded.timetable_key
        RETURNING * INTO v_group;
        INSERT INTO public.memberships(group_id, user_id, role)
        VALUES(v_group.id, v_user, 'member')
        ON CONFLICT (group_id, user_id) DO NOTHING;
        RETURN NEXT v_group;
    END LOOP;
END;
$$;

