/** Durable cleanup: Storage deletes bytes; the RPC finishes metadata only after it succeeds. */
export type CleanupConfig = {
  supabaseUrl: string;
  anonKey: string;
  serviceRoleKey: string;
};

type CleanupJob = {
  id: string;
  storage_path: string;
  state: "pending" | "completed";
};

type Fetcher = typeof fetch;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const requestTimeoutMs = 5_000;
const batchSize = 50;
const parallelJobs = 5;

export function readCleanupConfig(get: (name: string) => string | undefined): CleanupConfig | null {
  const supabaseUrl = get("SUPABASE_URL")?.replace(/\/+$/, "");
  const serviceRoleKey = get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceRoleKey) return null;
  return { supabaseUrl, serviceRoleKey, anonKey: get("SUPABASE_ANON_KEY") ?? "" };
}

export function createDeleteHandler(config: CleanupConfig | null, fetcher: Fetcher = fetch) {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    const authorization = request.headers.get("Authorization") ?? "";
    if (!/^Bearer \S+$/i.test(authorization)) return json({ error: "not_authenticated" }, 401);
    if (!config?.anonKey) return json({ error: "not_configured" }, 503);
    try {
      const userHeaders = { Authorization: authorization, apikey: config.anonKey };
      const auth = await fetcher(`${config.supabaseUrl}/auth/v1/user`, {
        headers: userHeaders, signal: AbortSignal.timeout(requestTimeoutMs),
      });
      if (!auth.ok || typeof (await auth.json())?.id !== "string") {
        return json({ error: "not_authenticated" }, 401);
      }
      let fileId: string;
      try {
        const body = await request.json();
        if (typeof body?.fileId !== "string" || !uuid.test(body.fileId)) throw new Error();
        fileId = body.fileId;
      } catch {
        return json({ error: "invalid_request" }, 400);
      }
      // The user JWT authorizes membership/uploader access; never elevate preparation to service.
      const prepared = await fetcher(`${config.supabaseUrl}/rest/v1/rpc/prepare_group_file_deletion`, {
        method: "POST", headers: { ...userHeaders, "Content-Type": "application/json" },
        body: JSON.stringify({ p_file_id: fileId }), signal: AbortSignal.timeout(requestTimeoutMs),
      });
      if (!prepared.ok) {
        const status = prepared.status === 401 ? 401 : prepared.status === 403 ? 403 : 503;
        return json({ error: status === 503 ? "file_delete_unavailable" : "file_delete_denied" }, status);
      }
      const job = parseJob(await prepared.json());
      if (!await cleanJob(job, config, fetcher)) {
        return json({ error: "file_delete_pending", retryable: true }, 503);
      }
      return json({ deleted: true });
    } catch {
      // Never expose tokens, filenames, storage paths or upstream error bodies.
      return json({ error: "file_delete_unavailable", retryable: true }, 503);
    }
  };
}

export function createCleanupHandler(config: CleanupConfig | null, fetcher: Fetcher = fetch) {
  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    if (!config) return json({ error: "not_configured" }, 503);
    // This worker is scheduler/server only. An ordinary (even valid) user JWT cannot drain jobs.
    if (!sameString(request.headers.get("Authorization") ?? "", `Bearer ${config.serviceRoleKey}`)) {
      return json({ error: "not_authorized" }, 401);
    }
    try {
      const pending = await fetcher(
        // Failed jobs increment attempts so one failing batch cannot starve later cleanup.
        `${config.supabaseUrl}/rest/v1/group_file_cleanup_jobs?select=id,storage_path,state&state=eq.pending&order=attempts.asc,created_at.asc,id.asc&limit=${batchSize}`,
        { headers: serviceHeaders(config), signal: AbortSignal.timeout(requestTimeoutMs) },
      );
      if (!pending.ok) return json({ error: "cleanup_unavailable" }, 503);
      const rows = await pending.json();
      if (!Array.isArray(rows)) throw new Error();
      const jobs = rows.slice(0, batchSize).map(parseJob);
      let next = 0;
      let completed = 0;
      let failed = 0;
      await Promise.all(Array.from({ length: Math.min(parallelJobs, jobs.length) }, async () => {
        while (next < jobs.length) {
          const job = jobs[next++];
          if (await cleanJob(job, config, fetcher)) completed++;
          else failed++;
        }
      }));
      return json({ processed: jobs.length, completed, failed }, failed ? 503 : 200);
    } catch {
      return json({ error: "cleanup_unavailable" }, 503);
    }
  };
}

async function cleanJob(job: CleanupJob, config: CleanupConfig, fetcher: Fetcher): Promise<boolean> {
  if (job.state === "completed") return true;
  let error = "storage_delete_failed";
  try {
    // The bulk API accepts exact object keys and succeeds with [] when already absent.
    // This makes retry safe after Storage success followed by a lost completion response.
    const removed = await fetcher(`${config.supabaseUrl}/storage/v1/object/group-files`, {
      method: "DELETE", headers: serviceHeaders(config),
      body: JSON.stringify({ prefixes: [job.storage_path] }), signal: AbortSignal.timeout(requestTimeoutMs),
    });
    if (removed.ok) {
      error = "cleanup_completion_failed";
      if (await finishJob(job.id, null, config, fetcher)) return true;
    }
  } catch {
    // The prepared job is already durable. Failure stays pending even if recording it also fails.
  }
  try { await finishJob(job.id, error, config, fetcher); } catch { /* Retried by caller/worker. */ }
  return false;
}

async function finishJob(id: string, error: string | null, config: CleanupConfig, fetcher: Fetcher) {
  const response = await fetcher(`${config.supabaseUrl}/rest/v1/rpc/finish_group_file_cleanup`, {
    method: "POST", headers: serviceHeaders(config),
    body: JSON.stringify({ p_job_id: id, p_error: error }), signal: AbortSignal.timeout(requestTimeoutMs),
  });
  return response.ok;
}

function parseJob(value: unknown): CleanupJob {
  const job = value as Partial<CleanupJob> | null;
  if (!job || typeof job.id !== "string" || !uuid.test(job.id) ||
    typeof job.storage_path !== "string" || !job.storage_path || job.storage_path.length > 1024 ||
    !["pending", "completed"].includes(job.state ?? "")) throw new Error("Invalid cleanup job");
  return job as CleanupJob;
}

function serviceHeaders(config: CleanupConfig) {
  return { Authorization: `Bearer ${config.serviceRoleKey}`, apikey: config.serviceRoleKey, "Content-Type": "application/json" };
}

function sameString(left: string, right: string) {
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  if (a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a[i] ^ b[i];
  return difference === 0;
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}
