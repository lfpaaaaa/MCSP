-- Fair-use limits, pacing and cache for the route-eta Edge Function. The function gets travel times
-- from free services run by volunteers: OSRM on routing.openstreetmap.de (FOSSGIS) for walking and
-- driving, and Transitous for public transport. Neither charges or needs a key, but both ask for
-- light use, and FOSSGIS allows at most one request per second. Before every call the function asks
-- the database: private.consume_route_quota applies monthly, daily and per-person limits, and
-- private.reserve_route_slot starts calls to each service at least a second apart. Only the
-- database owner, which the Edge Function connects as, can use these objects; app users have no
-- access to them.

CREATE TABLE private.route_limits (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    monthly_total INTEGER NOT NULL CHECK (monthly_total >= 0),
    monthly_transit INTEGER NOT NULL CHECK (monthly_transit >= 0),
    daily_total INTEGER NOT NULL CHECK (daily_total >= 0),
    daily_per_user INTEGER NOT NULL CHECK (daily_per_user >= 0)
);

COMMENT ON TABLE private.route_limits IS
    'Fair-use limits for calls to the routing services. Edit the single row to change them without redeploying.';

INSERT INTO private.route_limits (id, monthly_total, monthly_transit, daily_total, daily_per_user)
VALUES (1, 9000, 3000, 300, 60);

CREATE TABLE private.route_usage_monthly (
    month DATE PRIMARY KEY,
    total INTEGER NOT NULL DEFAULT 0,
    transit INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE private.route_usage_daily (
    day DATE NOT NULL,
    user_id UUID NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (day, user_id)
);

CREATE TABLE private.route_cache (
    cache_key TEXT PRIMARY KEY,
    duration_seconds INTEGER NOT NULL CHECK (duration_seconds >= 0),
    distance_meters INTEGER NOT NULL CHECK (distance_meters >= 0),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX route_cache_expires_at_idx ON private.route_cache (expires_at);

-- ---------------------------------------------------------------------------
-- Budget
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.consume_route_quota(p_user_id UUID, p_transit BOOLEAN)
RETURNS TABLE (allowed BOOLEAN, reason TEXT, retry_after_seconds INTEGER)
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
    v_now TIMESTAMPTZ := now();
    v_day DATE := (v_now AT TIME ZONE 'UTC')::DATE;
    v_month DATE := date_trunc('month', v_now AT TIME ZONE 'UTC')::DATE;
    v_next_day TIMESTAMPTZ := (v_day + 1)::TIMESTAMP AT TIME ZONE 'UTC';
    v_next_month TIMESTAMPTZ := (v_month + INTERVAL '1 month') AT TIME ZONE 'UTC';
    v_limits private.route_limits;
    v_usage private.route_usage_monthly;
    v_day_total INTEGER;
    v_user_day INTEGER;
BEGIN
    SELECT * INTO v_limits FROM private.route_limits WHERE id = 1;

    -- One row per month, locked so that concurrent requests are counted one at a time.
    INSERT INTO private.route_usage_monthly (month) VALUES (v_month) ON CONFLICT (month) DO NOTHING;
    SELECT * INTO v_usage FROM private.route_usage_monthly WHERE month = v_month FOR UPDATE;

    v_day_total := coalesce((SELECT sum(d.requests) FROM private.route_usage_daily AS d WHERE d.day = v_day), 0);
    v_user_day := coalesce(
        (SELECT d.requests FROM private.route_usage_daily AS d WHERE d.day = v_day AND d.user_id = p_user_id),
        0
    );

    IF v_usage.total >= v_limits.monthly_total THEN
        RETURN QUERY SELECT false, 'monthly'::TEXT, ceil(extract(epoch FROM v_next_month - v_now))::INTEGER;
        RETURN;
    END IF;

    IF p_transit AND v_usage.transit >= v_limits.monthly_transit THEN
        RETURN QUERY SELECT false, 'monthly_transit'::TEXT, ceil(extract(epoch FROM v_next_month - v_now))::INTEGER;
        RETURN;
    END IF;

    IF v_day_total >= v_limits.daily_total THEN
        RETURN QUERY SELECT false, 'daily'::TEXT, ceil(extract(epoch FROM v_next_day - v_now))::INTEGER;
        RETURN;
    END IF;

    IF v_user_day >= v_limits.daily_per_user THEN
        RETURN QUERY SELECT false, 'user_daily'::TEXT, ceil(extract(epoch FROM v_next_day - v_now))::INTEGER;
        RETURN;
    END IF;

    UPDATE private.route_usage_monthly
    SET total = total + 1,
        transit = transit + CASE WHEN p_transit THEN 1 ELSE 0 END
    WHERE month = v_month;

    INSERT INTO private.route_usage_daily (day, user_id, requests)
    VALUES (v_day, p_user_id, 1)
    ON CONFLICT (day, user_id) DO UPDATE SET requests = private.route_usage_daily.requests + 1;

    -- Daily counts are only needed for the current day; keep a month for troubleshooting.
    DELETE FROM private.route_usage_daily WHERE day < v_day - 31;

    RETURN QUERY SELECT true, NULL::TEXT, 0;
END;
$$;

COMMENT ON FUNCTION private.consume_route_quota(UUID, BOOLEAN) IS
    'Counts one routing call if the monthly, transit, daily and per-person limits allow it.';

-- ---------------------------------------------------------------------------
-- Cache
-- ---------------------------------------------------------------------------

CREATE FUNCTION private.cached_route(p_cache_key TEXT)
RETURNS TABLE (duration_seconds INTEGER, distance_meters INTEGER)
LANGUAGE sql
STABLE
SET search_path = ''
AS $$
    SELECT c.duration_seconds, c.distance_meters
    FROM private.route_cache AS c
    WHERE c.cache_key = p_cache_key
      AND c.expires_at > now();
$$;

CREATE FUNCTION private.store_route(
    p_cache_key TEXT,
    p_duration_seconds INTEGER,
    p_distance_meters INTEGER,
    p_ttl_seconds INTEGER
)
RETURNS VOID
LANGUAGE sql
SET search_path = ''
AS $$
    INSERT INTO private.route_cache (cache_key, duration_seconds, distance_meters, expires_at)
    VALUES (p_cache_key, p_duration_seconds, p_distance_meters, now() + make_interval(secs => p_ttl_seconds))
    ON CONFLICT (cache_key) DO UPDATE
    SET duration_seconds = EXCLUDED.duration_seconds,
        distance_meters = EXCLUDED.distance_meters,
        expires_at = EXCLUDED.expires_at;

    DELETE FROM private.route_cache WHERE expires_at < now() - INTERVAL '1 day';
$$;

-- ---------------------------------------------------------------------------
-- Pacing
-- ---------------------------------------------------------------------------

CREATE TABLE private.route_providers (
    provider TEXT PRIMARY KEY,
    min_interval_ms INTEGER NOT NULL CHECK (min_interval_ms >= 0),
    next_slot_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity'
);

COMMENT ON TABLE private.route_providers IS
    'When the next call to each routing service may start. Calls start at least min_interval_ms apart.';

INSERT INTO private.route_providers (provider, min_interval_ms)
VALUES ('osrm', 1000), ('transitous', 1000);

CREATE FUNCTION private.reserve_route_slot(p_provider TEXT, p_max_wait_ms INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
SET search_path = ''
AS $$
DECLARE
    v_provider private.route_providers;
    v_now TIMESTAMPTZ;
    v_slot TIMESTAMPTZ;
BEGIN
    -- The row lock makes concurrent requests take their turns one after another.
    SELECT * INTO v_provider FROM private.route_providers WHERE provider = p_provider FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown routing provider: %', p_provider USING ERRCODE = '22023';
    END IF;

    v_now := clock_timestamp();
    v_slot := greatest(v_now, v_provider.next_slot_at);
    IF v_slot > v_now + p_max_wait_ms * INTERVAL '1 millisecond' THEN
        RETURN NULL;
    END IF;

    UPDATE private.route_providers
    SET next_slot_at = v_slot + v_provider.min_interval_ms * INTERVAL '1 millisecond'
    WHERE provider = p_provider;

    RETURN ceil(extract(epoch FROM v_slot - v_now) * 1000)::INTEGER;
END;
$$;

COMMENT ON FUNCTION private.reserve_route_slot(TEXT, INTEGER) IS
    'Books the next turn to call a routing service. Returns the milliseconds to wait before calling, or NULL when the wait would be longer than p_max_wait_ms.';

-- ---------------------------------------------------------------------------
-- Privileges: only the owner uses these objects
-- ---------------------------------------------------------------------------

REVOKE ALL ON TABLE
    private.route_limits,
    private.route_usage_monthly,
    private.route_usage_daily,
    private.route_cache,
    private.route_providers
FROM PUBLIC, anon, authenticated;

REVOKE ALL ON FUNCTION private.consume_route_quota(UUID, BOOLEAN) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION private.cached_route(TEXT) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION private.store_route(TEXT, INTEGER, INTEGER, INTEGER) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION private.reserve_route_slot(TEXT, INTEGER) FROM PUBLIC, anon, authenticated;
