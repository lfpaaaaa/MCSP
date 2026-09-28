// route-eta: travel time between two points from free, community-run routing services.
//
// Walking and driving times come from the FOSSGIS OSRM servers and public transport times from
// Transitous (see providers.ts). Neither needs an API key. Both are run by volunteers, so the
// function uses them sparingly: positions are rounded to about 110 m, answers are cached in the
// database, every call must fit the fair-use limits in private.consume_route_quota, and calls to
// each service start at least a second apart (private.reserve_route_slot). When a limit is reached
// the function answers 429 with `retry_after_seconds`, and the app uses its offline estimate.
//
// Transitous asks projects to contact its team before using its routing, so public transport
// routing stays off until the TRANSITOUS_ENABLED secret is "true"; until then TRANSIT requests
// answer 503.

import postgres from "npm:postgres@3.4.5";
import { type Mode, type Point, providerFor, type RoutingProvider, type RoutingResult } from "./providers.ts";

interface RouteRequest {
  origin: Point;
  destination: Point;
  mode: Mode;
}

const MODES: readonly Mode[] = ["WALK", "TRANSIT", "DRIVE"];
// Walking and driving times (without live traffic) do not change; transit times follow the timetable.
const CACHE_SECONDS: Record<Mode, number> = { WALK: 86_400, DRIVE: 86_400, TRANSIT: 600 };
// How long a request may wait for its turn with a routing service before it gives up.
const MAX_SLOT_WAIT_MS = 3_000;
// Suggested wait when a routing service is busy with other people's requests.
const BUSY_RETRY_SECONDS = 5;
const PROVIDER_TIMEOUT_MS = 10_000;
// Both services ask for a User-Agent that names the app and says how to reach its developers.
const USER_AGENT = "CampusCompanion/0.1 (COMP90018 student project; https://github.com/lfpaaaaa/MCSP)";
// Test calls made with the service role, for example from the dashboard, are counted under this id.
const SERVICE_USER_ID = "00000000-0000-0000-0000-000000000000";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 1 });

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }

  const userId = callerId(request);
  if (!userId) {
    return json({ error: "not_authenticated" }, 401);
  }

  let route: RouteRequest;
  try {
    route = parseRouteRequest(await request.json());
  } catch {
    return json({ error: "invalid_request" }, 400);
  }

  // Read on every request, so switching the secret takes effect without a redeploy.
  if (route.mode === "TRANSIT" && Deno.env.get("TRANSITOUS_ENABLED") !== "true") {
    return json({ error: "not_configured" }, 503);
  }

  try {
    const key = cacheKey(route);
    const [cached] = await sql`
      select duration_seconds, distance_meters from private.cached_route(${key})
    `;
    if (cached) {
      return json({
        duration_seconds: cached.duration_seconds,
        distance_meters: cached.distance_meters,
        cached: true,
      });
    }

    const [quota] = await sql`
      select allowed, reason, retry_after_seconds
      from private.consume_route_quota(${userId}::uuid, ${route.mode === "TRANSIT"})
    `;
    if (!quota.allowed) {
      return tryLater(quota.reason, quota.retry_after_seconds);
    }

    const provider = providerFor(route.mode);
    const [slot] = await sql`
      select private.reserve_route_slot(${provider.name}, ${MAX_SLOT_WAIT_MS}) as wait_ms
    `;
    if (slot.wait_ms === null) {
      return tryLater("provider_busy", BUSY_RETRY_SECONDS);
    }
    await sleep(slot.wait_ms);

    const result = await askProvider(provider, route);
    if (result.kind === "no_route") {
      return json({ error: "no_route" }, 404);
    }
    if (result.kind === "failed") {
      console.error("Routing failed", provider.name, result.detail);
      return json({ error: "routing_failed" }, 502);
    }

    const { durationSeconds, distanceMeters } = result.route;
    await sql`
      select private.store_route(${key}, ${durationSeconds}, ${distanceMeters}, ${CACHE_SECONDS[route.mode]})
    `;
    return json({ duration_seconds: durationSeconds, distance_meters: distanceMeters, cached: false });
  } catch (error) {
    console.error("route-eta failed", error);
    return json({ error: "internal_error" }, 500);
  }
});

async function askProvider(provider: RoutingProvider, route: RouteRequest): Promise<RoutingResult> {
  try {
    const response = await fetch(provider.url(route.origin, route.destination), {
      headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
      signal: AbortSignal.timeout(PROVIDER_TIMEOUT_MS),
    });
    const body = await response.json().catch(() => null);
    return provider.read(response.status, body);
  } catch (error) {
    return { kind: "failed", detail: `No answer: ${error}` };
  }
}

/** The caller's user id. The platform has already verified the token (verify_jwt is on). */
function callerId(request: Request): string | null {
  const token = request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  const claims = decodeJwtPayload(token);
  if (claims?.role === "authenticated" && typeof claims.sub === "string") {
    return claims.sub;
  }
  if (claims?.role === "service_role") {
    return SERVICE_USER_ID;
  }
  return null;
}

function decodeJwtPayload(token: string): Record<string, unknown> | null {
  const part = token.split(".")[1];
  if (!part) {
    return null;
  }
  try {
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    return JSON.parse(atob(padded));
  } catch {
    return null;
  }
}

function parseRouteRequest(body: unknown): RouteRequest {
  const input = (body ?? {}) as Record<string, unknown>;
  const mode = input.mode;
  if (typeof mode !== "string" || !MODES.includes(mode as Mode)) {
    throw new Error("Unknown travel mode");
  }
  return { origin: parsePoint(input.origin), destination: parsePoint(input.destination), mode: mode as Mode };
}

function parsePoint(value: unknown): Point {
  const point = (value ?? {}) as Record<string, unknown>;
  const lat = Number(point.lat);
  const lng = Number(point.lng);
  if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
    throw new Error("Invalid coordinate");
  }
  // The app already sends rounded positions; rounding again keeps the server side safe as well.
  return { lat: roundToThreeDecimals(lat), lng: roundToThreeDecimals(lng) };
}

function roundToThreeDecimals(value: number): number {
  return Math.round(value * 1000) / 1000;
}

function cacheKey({ origin, destination, mode }: RouteRequest): string {
  return `${mode}:${origin.lat.toFixed(3)},${origin.lng.toFixed(3)}>${destination.lat.toFixed(3)},${destination.lng.toFixed(3)}`;
}

/** 429 with the number of seconds after which routing is worth trying again. */
function tryLater(scope: string, retryAfterSeconds: number): Response {
  return json(
    { error: "quota_exceeded", scope, retry_after_seconds: retryAfterSeconds },
    429,
    { "Retry-After": String(retryAfterSeconds) },
  );
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, milliseconds)));
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}
