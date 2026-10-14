import { createDeleteHandler, readCleanupConfig } from "../_shared/group-file-cleanup.ts";

// Auth /user verifies both legacy and signing-key JWTs before the user-authorized preparation RPC.
Deno.serve(createDeleteHandler(readCleanupConfig((name) => Deno.env.get(name))));
