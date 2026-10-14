import { type CleanupConfig, createCleanupHandler, createDeleteHandler } from "./group-file-cleanup.ts";

const fileId = "00000000-0000-0000-0000-000000000001";
const jobId = "00000000-0000-0000-0000-000000000002";
const storagePath = "group/uploader/private-file";
const config: CleanupConfig = {
  supabaseUrl: "https://project.example", anonKey: "anon-key", serviceRoleKey: "server-only-secret",
};

function assertEquals(actual: unknown, expected: unknown) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`Expected ${JSON.stringify(expected)}, received ${JSON.stringify(actual)}`);
  }
}

function deleteRequest(id = fileId) {
  return new Request("https://functions.example/group-file-delete", {
    method: "POST", headers: { Authorization: "Bearer user-token", "Content-Type": "application/json" },
    body: JSON.stringify({ fileId: id }),
  });
}

function cleanupRequest(authorization = `Bearer ${config.serviceRoleKey}`) {
  return new Request("https://functions.example/group-file-cleanup", {
    method: "POST", headers: { Authorization: authorization },
  });
}

class FakeServer {
  storagePresent = true;
  metadataPresent = true;
  completed = false;
  storageFails = false;
  denyPreparation = false;
  authFails = false;
  loseFinishResponse = false;
  finishCommittedBeforeLoss = false;
  errors: string[] = [];
  calls: { path: string; method: string; authorization: string; body: unknown }[] = [];

  fetch: typeof fetch = (input, init) => Promise.resolve().then(() => {
    const url = new URL(String(input));
    const headers = new Headers(init?.headers);
    const body = typeof init?.body === "string" ? JSON.parse(init.body) : null;
    const call = { path: url.pathname, method: init?.method ?? "GET", authorization: headers.get("Authorization") ?? "", body };
    this.calls.push(call);
    if (url.pathname === "/auth/v1/user") {
      assertEquals(call.authorization, "Bearer user-token");
      assertEquals(headers.get("apikey"), config.anonKey);
      return this.authFails ? response({}, 401) : response({ id: "user-id" });
    }
    if (url.pathname.endsWith("/prepare_group_file_deletion")) {
      assertEquals(call.authorization, "Bearer user-token");
      assertEquals(body, { p_file_id: fileId });
      if (this.denyPreparation) return response({ message: "not_the_uploader" }, 403);
      return response({ id: jobId, storage_path: storagePath, state: this.completed ? "completed" : "pending" });
    }
    assertEquals(call.authorization, `Bearer ${config.serviceRoleKey}`);
    assertEquals(headers.get("apikey"), config.serviceRoleKey);
    if (url.pathname === "/storage/v1/object/group-files") {
      assertEquals(call.method, "DELETE");
      assertEquals(body, { prefixes: [storagePath] });
      if (this.storageFails) return response({ error: "private upstream error with storage key and token" }, 500);
      const removed = this.storagePresent;
      this.storagePresent = false;
      return response(removed ? [{ name: storagePath }] : []);
    }
    if (url.pathname.endsWith("/finish_group_file_cleanup")) {
      assertEquals(body.p_job_id, jobId);
      if (body.p_error !== null) {
        this.errors.push(body.p_error);
        return response(null);
      }
      if (this.loseFinishResponse) {
        this.loseFinishResponse = false;
        if (this.finishCommittedBeforeLoss) this.finish();
        throw new TypeError("Connection lost after response; private detail");
      }
      this.finish();
      return response(null);
    }
    if (url.pathname === "/rest/v1/group_file_cleanup_jobs") {
      assertEquals(url.searchParams.get("state"), "eq.pending");
      assertEquals(url.searchParams.get("limit"), "50");
      assertEquals(url.searchParams.get("order"), "attempts.asc,created_at.asc,id.asc");
      return response(this.completed ? [] : [{ id: jobId, storage_path: storagePath, state: "pending" }]);
    }
    throw new Error(`Unexpected path ${url.pathname}`);
  });

  count(path: string) { return this.calls.filter((call) => call.path.endsWith(path)).length; }
  private finish() { this.metadataPresent = false; this.completed = true; }
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

Deno.test("Storage failure stays pending with metadata and a sanitized retryable error", async () => {
  const server = new FakeServer();
  server.storageFails = true;
  const result = await createDeleteHandler(config, server.fetch)(deleteRequest());

  assertEquals(result.status, 503);
  assertEquals(await result.json(), { error: "file_delete_pending", retryable: true });
  assertEquals(server.errors, ["storage_delete_failed"]);
  assertEquals(server.metadataPresent, true);
  assertEquals(server.storagePresent, true);
  assertEquals(server.completed, false);
  server.storageFails = false;
  assertEquals((await createDeleteHandler(config, server.fetch)(deleteRequest())).status, 200);
  assertEquals(server.metadataPresent, false);
});

Deno.test("lost uncommitted completion response retries an already absent object safely", async () => {
  const server = new FakeServer();
  server.loseFinishResponse = true;
  const handler = createDeleteHandler(config, server.fetch);

  assertEquals((await handler(deleteRequest())).status, 503);
  assertEquals(server.errors, ["cleanup_completion_failed"]);
  assertEquals(server.storagePresent, false);
  assertEquals(server.metadataPresent, true);
  assertEquals((await handler(deleteRequest())).status, 200);
  assertEquals(server.count("/object/group-files"), 2);
  assertEquals(server.metadataPresent, false);
  assertEquals(server.completed, true);
});

Deno.test("lost committed completion response returns idempotent success on retry", async () => {
  const server = new FakeServer();
  server.loseFinishResponse = true;
  server.finishCommittedBeforeLoss = true;
  const handler = createDeleteHandler(config, server.fetch);

  assertEquals((await handler(deleteRequest())).status, 503);
  assertEquals(server.completed, true);
  assertEquals((await handler(deleteRequest())).status, 200);
  assertEquals(server.count("/object/group-files"), 1);
  assertEquals(server.metadataPresent, false);
});

Deno.test("already completed job succeeds without touching Storage or completion RPC", async () => {
  const server = new FakeServer();
  server.completed = true;
  server.metadataPresent = false;
  const result = await createDeleteHandler(config, server.fetch)(deleteRequest());

  assertEquals(await result.json(), { deleted: true });
  assertEquals(server.count("/object/group-files"), 0);
  assertEquals(server.count("/finish_group_file_cleanup"), 0);
});

Deno.test("wrong uploader is denied by user-authorized preparation before any Storage call", async () => {
  const server = new FakeServer();
  server.denyPreparation = true;
  const result = await createDeleteHandler(config, server.fetch)(deleteRequest());

  assertEquals(result.status, 403);
  assertEquals(server.count("/auth/v1/user"), 1);
  assertEquals(server.count("/object/group-files"), 0);
  assertEquals(server.count("/finish_group_file_cleanup"), 0);
});

Deno.test("invalid Auth credentials cannot reach deletion preparation", async () => {
  const server = new FakeServer();
  server.authFails = true;
  assertEquals((await createDeleteHandler(config, server.fetch)(deleteRequest())).status, 401);
  assertEquals(server.count("/prepare_group_file_deletion"), 0);
});

Deno.test("invalid file id cannot reach deletion preparation", async () => {
  const server = new FakeServer();
  assertEquals((await createDeleteHandler(config, server.fetch)(deleteRequest("../another-file"))).status, 400);
  assertEquals(server.count("/prepare_group_file_deletion"), 0);
});

Deno.test("worker rejects absent, user and forged service credentials before reading jobs", async () => {
  const server = new FakeServer();
  const handler = createCleanupHandler(config, server.fetch);
  for (const authorization of ["", "Bearer user-token", "Bearer claimed-service-role", `Basic ${config.serviceRoleKey}`]) {
    assertEquals((await handler(cleanupRequest(authorization))).status, 401);
  }
  assertEquals(server.calls.length, 0);
});

Deno.test("worker drains pending jobs under service authorization", async () => {
  const server = new FakeServer();
  const result = await createCleanupHandler(config, server.fetch)(cleanupRequest());

  assertEquals(result.status, 200);
  assertEquals(await result.json(), { processed: 1, completed: 1, failed: 0 });
  assertEquals(server.completed, true);
  assertEquals(server.metadataPresent, false);
  assertEquals(server.storagePresent, false);
});

Deno.test("worker reports partial failure while retaining retryable cleanup", async () => {
  const server = new FakeServer();
  server.storageFails = true;
  const result = await createCleanupHandler(config, server.fetch)(cleanupRequest());

  assertEquals(result.status, 503);
  assertEquals(await result.json(), { processed: 1, completed: 0, failed: 1 });
  assertEquals(server.errors, ["storage_delete_failed"]);
  assertEquals(server.metadataPresent, true);
});

Deno.test("worker enforces fifty job limit even when upstream returns more rows", async () => {
  let storageCalls = 0;
  const fetcher: typeof fetch = (input, init) => Promise.resolve().then(() => {
    const path = new URL(String(input)).pathname;
    assertEquals(new Headers(init?.headers).get("Authorization"), `Bearer ${config.serviceRoleKey}`);
    if (path === "/rest/v1/group_file_cleanup_jobs") {
      return response(Array.from({ length: 51 }, () => ({ id: jobId, storage_path: storagePath, state: "pending" })));
    }
    if (path === "/storage/v1/object/group-files") { storageCalls++; return response([]); }
    if (path.endsWith("/finish_group_file_cleanup")) return response(null);
    throw new Error("Unexpected request");
  });
  const result = await createCleanupHandler(config, fetcher)(cleanupRequest());

  assertEquals(result.status, 200);
  assertEquals(await result.json(), { processed: 50, completed: 50, failed: 0 });
  assertEquals(storageCalls, 50);
});
