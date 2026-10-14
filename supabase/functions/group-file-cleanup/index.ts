import { createCleanupHandler, readCleanupConfig } from "../_shared/group-file-cleanup.ts";

// Schedule this server-only worker with the service-role bearer credential kept in Vault.
Deno.serve(createCleanupHandler(readCleanupConfig((name) => Deno.env.get(name))));
