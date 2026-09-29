// notify-message: pushes a new group message to the other members' devices.
//
// The database calls this function from a trigger on messages (private.notify_new_message) with
// the message id and the key stored in Vault; the function verifies the key, reads the message
// and the recipients' device tokens, and sends one Firebase Cloud Messaging data message per
// device. Tokens that Firebase reports as no longer registered are removed.
//
// Secrets: NOTIFY_MESSAGE_KEY (shared with Vault) and FCM_SERVICE_ACCOUNT (the service account
// JSON from the Firebase console).

import postgres from "npm:postgres@3.4.5";
import { parseServiceAccount, sendToDevice, type SendOutcome } from "./fcm.ts";

const PREVIEW_LENGTH = 80;

const sql = postgres(Deno.env.get("SUPABASE_DB_URL")!, { prepare: false, max: 1 });

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }

  const expectedKey = Deno.env.get("NOTIFY_MESSAGE_KEY") ?? "";
  if (!expectedKey || !sameString(request.headers.get("x-notify-key") ?? "", expectedKey)) {
    return json({ error: "not_authorized" }, 401);
  }

  const serviceAccountJson = Deno.env.get("FCM_SERVICE_ACCOUNT");
  if (!serviceAccountJson) {
    return json({ error: "not_configured" }, 503);
  }

  let messageId: string;
  try {
    messageId = parseMessageId(await request.json());
  } catch {
    return json({ error: "invalid_request" }, 400);
  }

  try {
    const account = parseServiceAccount(serviceAccountJson);

    const [message] = await sql`
      select m.group_id, m.body, g.name as group_name, coalesce(p.display_name, 'A member') as sender_name
      from public.messages as m
      join public.groups as g on g.id = m.group_id
      left join public.profiles as p on p.id = m.sender_id
      where m.id = ${messageId}::uuid
    `;
    if (!message) {
      return json({ error: "not_found" }, 404);
    }

    const recipients = await sql`
      select t.token
      from public.messages as m
      join public.memberships as mb on mb.group_id = m.group_id and mb.user_id <> m.sender_id
      join public.device_tokens as t on t.user_id = mb.user_id
      where m.id = ${messageId}::uuid
    `;

    const data = {
      type: "group_message",
      message_id: messageId,
      group_id: String(message.group_id),
      group_name: String(message.group_name),
      sender_name: String(message.sender_name),
      preview: preview(String(message.body)),
    };

    const counts: Record<SendOutcome, number> = { sent: 0, unregistered: 0, failed: 0 };
    for (const recipient of recipients) {
      const outcome = await sendToDevice(account, recipient.token, data);
      counts[outcome] += 1;
      if (outcome === "unregistered") {
        await sql`delete from public.device_tokens where token = ${recipient.token}`;
      }
    }
    return json({ sent: counts.sent, removed: counts.unregistered, failed: counts.failed });
  } catch (error) {
    console.error("notify-message failed", error);
    return json({ error: "internal_error" }, 500);
  }
});

function parseMessageId(body: unknown): string {
  const message = (body as { message?: { id?: unknown } } | null)?.message;
  const id = message?.id;
  if (typeof id !== "string" || !/^[0-9a-f-]{36}$/i.test(id)) {
    throw new Error("Invalid message id");
  }
  return id;
}

/** The first line of the message, shortened for a notification. */
export function preview(body: string): string {
  const firstLine = body.trim().split(/\r?\n/)[0] ?? "";
  return firstLine.length > PREVIEW_LENGTH ? `${firstLine.slice(0, PREVIEW_LENGTH - 1)}…` : firstLine;
}

/** Compares two strings in time that does not depend on where they differ. */
function sameString(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  if (left.length !== right.length) {
    return false;
  }
  let difference = 0;
  for (let i = 0; i < left.length; i++) {
    difference |= left[i] ^ right[i];
  }
  return difference === 0;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}
