// Firebase Cloud Messaging (HTTP v1): access tokens from a service account and single-device sends.

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri?: string;
}

export type SendOutcome = "sent" | "unregistered" | "failed";

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const MESSAGING_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_LIFETIME_SECONDS = 3600;

let cachedToken: { value: string; expiresAt: number } | null = null;

/** Reads the service account JSON that the Firebase console generates. */
export function parseServiceAccount(json: string): ServiceAccount {
  const account = JSON.parse(json);
  for (const field of ["project_id", "client_email", "private_key"]) {
    if (typeof account[field] !== "string" || account[field].length === 0) {
      throw new Error(`Service account is missing ${field}`);
    }
  }
  return account;
}

/** Sends a data message to one device. Values must be strings. */
export async function sendToDevice(
  account: ServiceAccount,
  token: string,
  data: Record<string, string>,
): Promise<SendOutcome> {
  const accessToken = await getAccessToken(account);
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { token, data, android: { priority: "high" } } }),
    },
  );
  if (response.ok) {
    return "sent";
  }
  const detail = await response.text();
  // The app was uninstalled or the token was rotated; the caller drops the token.
  if (response.status === 404 || detail.includes("UNREGISTERED") || detail.includes("registration-token-not-registered")) {
    return "unregistered";
  }
  console.error("FCM send failed", response.status, detail);
  return "failed";
}

async function getAccessToken(account: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt - 60 > now) {
    return cachedToken.value;
  }
  const response = await fetch(account.token_uri ?? TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: await signAssertion(account, now),
    }),
  });
  if (!response.ok) {
    throw new Error(`Access token request failed: ${response.status} ${await response.text()}`);
  }
  const body = await response.json();
  cachedToken = { value: body.access_token, expiresAt: now + Number(body.expires_in ?? TOKEN_LIFETIME_SECONDS) };
  return cachedToken.value;
}

/** A JWT signed with the service account's key, which Google exchanges for an access token. */
export async function signAssertion(account: ServiceAccount, issuedAt: number): Promise<string> {
  const header = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = base64url(JSON.stringify({
    iss: account.client_email,
    scope: MESSAGING_SCOPE,
    aud: account.token_uri ?? TOKEN_URL,
    iat: issuedAt,
    exp: issuedAt + TOKEN_LIFETIME_SECONDS,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claims}`),
  );
  return `${header}.${claims}.${base64url(signature)}`;
}

function pemToDer(pem: string): ArrayBuffer {
  const base64 = pem.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

function base64url(input: string | ArrayBuffer): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
