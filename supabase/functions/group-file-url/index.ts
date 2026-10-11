// Only this server-side function signs preview URLs for attachments with downloads disabled.
// Preview URLs necessarily deliver bytes to the viewer; this is not DRM or screenshot prevention.
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
});
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  try {
    const base = Deno.env.get("SUPABASE_URL")!;
    const headers = { Authorization: request.headers.get("Authorization") ?? "", apikey: Deno.env.get("SUPABASE_ANON_KEY")! };
    const auth = await fetch(`${base}/auth/v1/user`, { headers, signal: AbortSignal.timeout(5_000) });
    if (!auth.ok) return json({ error: "not_authenticated" }, 401);
    if (!(await auth.json()).id) return json({ error: "not_authenticated" }, 401);
    let body;
    try {
      body = await request.json();
      if (typeof body.fileId !== "string" || !uuid.test(body.fileId) || !["preview", "download"].includes(body.purpose)) throw new Error();
    } catch { return json({ error: "invalid_request" }, 400); }
    const access = await fetch(`${base}/rest/v1/rpc/group_file_access`, {
      method: "POST", headers: { ...headers, "Content-Type": "application/json" },
      body: JSON.stringify({ p_file_id: body.fileId, p_download: body.purpose === "download" }),
      signal: AbortSignal.timeout(5_000),
    });
    if (!access.ok) {
      const error = await access.json().catch(() => ({}));
      if (error.message === "downloads_disabled") return json({ error: "downloads_disabled" }, 403);
      return json({ error: "file_access_denied" }, access.status === 401 ? 401 : 403);
    }
    const path = await access.json();
    if (typeof path !== "string") return json({ error: "file_access_denied" }, 403);
    const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
    const signed = await fetch(`${base}/storage/v1/object/sign/group-files/${path.split("/").map(encodeURIComponent).join("/")}`, {
      method: "POST", headers: { Authorization: `Bearer ${serviceKey}`, apikey: serviceKey, "Content-Type": "application/json" },
      body: JSON.stringify({ expiresIn: body.purpose === "preview" ? 60 : 120 }),
      signal: AbortSignal.timeout(5_000),
    });
    if (!signed.ok) return json({ error: "file_unavailable" }, 404);
    const payload = await signed.json();
    const relative = payload.signedURL ?? payload.signedUrl;
    if (typeof relative !== "string" || !relative.startsWith("/object/sign/group-files/")) return json({ error: "signing_failed" }, 502);
    return json({ signedPath: `/storage/v1${relative}` });
  } catch {
    // Do not log access tokens, signed URLs, filenames or storage keys.
    return json({ error: "file_access_unavailable" }, 502);
  }
});
