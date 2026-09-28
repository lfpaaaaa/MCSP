BEGIN;

SELECT plan(21);

-- Small limits so that every rule can be reached in a few requests.
UPDATE private.route_limits
SET monthly_total = 4, monthly_transit = 1, daily_total = 3, daily_per_user = 2
WHERE id = 1;

-- Budget ------------------------------------------------------------------------

SELECT is(
    (SELECT allowed FROM private.consume_route_quota('a1000000-0000-4000-8000-000000000001', false)),
    true,
    'a request inside the budget is allowed'
);

SELECT is(
    (SELECT allowed FROM private.consume_route_quota('a1000000-0000-4000-8000-000000000001', false)),
    true,
    'a second request from the same person is allowed'
);

SELECT is(
    (SELECT reason FROM private.consume_route_quota('a1000000-0000-4000-8000-000000000001', false)),
    'user_daily',
    'one person cannot use up the whole daily budget'
);

SELECT is(
    (SELECT allowed FROM private.consume_route_quota('b2000000-0000-4000-8000-000000000002', true)),
    true,
    'another person can still ask for a transit route'
);

SELECT is(
    (SELECT reason FROM private.consume_route_quota('b2000000-0000-4000-8000-000000000002', true)),
    'monthly_transit',
    'transit requests have their own monthly limit'
);

SELECT is(
    (SELECT reason FROM private.consume_route_quota('b2000000-0000-4000-8000-000000000002', false)),
    'daily',
    'the daily limit applies to everyone together'
);

UPDATE private.route_limits SET daily_total = 10 WHERE id = 1;

SELECT is(
    (SELECT allowed FROM private.consume_route_quota('b2000000-0000-4000-8000-000000000002', false)),
    true,
    'raising a limit takes effect at once'
);

SELECT is(
    (SELECT reason FROM private.consume_route_quota('c3000000-0000-4000-8000-000000000003', false)),
    'monthly',
    'nothing is allowed once the monthly budget is used'
);

SELECT ok(
    (SELECT retry_after_seconds BETWEEN 1 AND 31 * 86400
     FROM private.consume_route_quota('c3000000-0000-4000-8000-000000000003', false)),
    'a refused request says when the budget resets'
);

SELECT is(
    (SELECT total FROM private.route_usage_monthly WHERE month = date_trunc('month', now() AT TIME ZONE 'UTC')::DATE),
    4,
    'refused requests are not counted'
);

-- Cache -------------------------------------------------------------------------

SELECT private.store_route('WALK:-37.798,144.961>-37.800,144.963', 420, 510, 60);

SELECT is(
    (SELECT duration_seconds FROM private.cached_route('WALK:-37.798,144.961>-37.800,144.963')),
    420,
    'a cached route is returned until it expires'
);

SELECT private.store_route('TRANSIT:-37.798,144.961>-37.810,144.960', 900, 2400, 0);

SELECT is(
    (SELECT count(*) FROM private.cached_route('TRANSIT:-37.798,144.961>-37.810,144.960')),
    0::bigint,
    'an expired route is not returned'
);

-- Pacing ------------------------------------------------------------------------

UPDATE private.route_providers SET next_slot_at = '-infinity';

SELECT is(
    private.reserve_route_slot('osrm', 3000),
    0,
    'the first call to a routing service can start at once'
);

-- BETWEEN would evaluate its left side twice, so each booking is made once in a FROM clause.
SELECT ok(
    (SELECT wait BETWEEN 900 AND 1000 FROM private.reserve_route_slot('osrm', 3000) AS wait),
    'the next call waits about a second'
);

SELECT is(
    private.reserve_route_slot('osrm', 500),
    NULL,
    'a call that would wait too long is turned away'
);

SELECT ok(
    (SELECT wait BETWEEN 1900 AND 2000 FROM private.reserve_route_slot('osrm', 3000) AS wait),
    'a call that was turned away does not take a turn'
);

SELECT is(
    private.reserve_route_slot('transitous', 3000),
    0,
    'each routing service keeps its own pace'
);

SELECT throws_ok(
    $$ SELECT private.reserve_route_slot('unknown', 3000) $$,
    '22023',
    NULL,
    'unknown routing services are rejected'
);

-- Privileges ----------------------------------------------------------------------

SELECT ok(
    NOT has_function_privilege('authenticated', 'private.consume_route_quota(uuid,boolean)', 'EXECUTE'),
    'app users cannot spend the routing budget directly'
);

SELECT ok(
    NOT has_table_privilege('authenticated', 'private.route_cache', 'SELECT'),
    'app users cannot read the route cache'
);

SELECT ok(
    NOT has_function_privilege('authenticated', 'private.reserve_route_slot(text,integer)', 'EXECUTE'),
    'app users cannot book routing turns directly'
);

SELECT * FROM finish();
ROLLBACK;
