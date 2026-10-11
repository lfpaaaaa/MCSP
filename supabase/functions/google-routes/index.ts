// Precise positions are forwarded only for an explicit route request. No route/GPS history is stored.
import postgres from "npm:postgres@3.4.5";

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 1 });
const fieldMask = [
  "routes.duration", "routes.distanceMeters", "routes.polyline.encodedPolyline",
  "routes.legs.steps.travelMode", "routes.legs.steps.navigationInstruction.instructions",
  "routes.legs.steps.polyline.encodedPolyline", "routes.legs.steps.transitDetails",
].join(",");
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
});

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  try {
    // Verify with Auth as well as the gateway, including deployments using signing-key JWTs.
    const auth = await fetch(`${Deno.env.get("SUPABASE_URL")}/auth/v1/user`, {
      headers: { Authorization: request.headers.get("Authorization") ?? "", apikey: Deno.env.get("SUPABASE_ANON_KEY")! },
      signal: AbortSignal.timeout(5_000),
    });
    if (!auth.ok) return json({ error: "not_authenticated" }, 401);
    const user = await auth.json();
    if (!user.id) return json({ error: "not_authenticated" }, 401);
    let body;
    try {
      body = await request.json();
      if (!["WALK", "DRIVE", "TRANSIT"].includes(body.mode)) throw new Error();
      for (const p of [body.origin, body.destination]) {
        if (typeof p?.latitude !== "number" || typeof p?.longitude !== "number" ||
          !Number.isFinite(p.latitude) || !Number.isFinite(p.longitude) ||
          Math.abs(p.latitude) > 90 || Math.abs(p.longitude) > 180) throw new Error();
      }
    } catch { return json({ error: "invalid_request" }, 400); }
    const key = Deno.env.get("GOOGLE_ROUTES_API_KEY");
    if (!key) return json({ error: "google_routes_not_configured" }, 503);
    const [quota] = await sql`
      select allowed, retry_after_seconds from private.consume_route_quota(${user.id}::uuid, ${body.mode === "TRANSIT"})
    `;
    if (!quota?.allowed) return json({ error: "quota_exceeded", retry_after_seconds: quota?.retry_after_seconds ?? 3600 }, 429);
    const response = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Goog-Api-Key": key, "X-Goog-FieldMask": body.etaOnly === true ? "routes.duration,routes.distanceMeters" : fieldMask },
      body: JSON.stringify({
        origin: { location: { latLng: body.origin } },
        destination: { location: { latLng: body.destination } },
        travelMode: body.mode,
        computeAlternativeRoutes: body.etaOnly !== true,
        polylineQuality: "HIGH_QUALITY",
        languageCode: "en-AU",
        ...(body.mode === "DRIVE" ? { routingPreference: "TRAFFIC_AWARE" } : {}),
      }),
      signal: AbortSignal.timeout(15_000),
    });
    if (!response.ok) return json({ error: "google_routes_unavailable" }, 502);
    const routes = await response.json();
    if (!routes.routes?.length) return json({ error: "no_route" }, 404);
    return json(routes);
  } catch {
    // Never log precise locations, access tokens, upstream bodies or API keys.
    return json({ error: "routing_failed" }, 502);
  }
});
