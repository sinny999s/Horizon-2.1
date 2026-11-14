/**
 * Sign-in tokens: `<uuid>.<expiry>.<HMAC(TOKEN_SECRET)>`, handed out by /auth once an account has proven itself and
 * good for a day. Shared by every route that needs to know who is asking.
 */
export const TOKEN_TTL = 24 * 3600;

export const now = () => Math.floor(Date.now() / 1000);
export const uuidRe = /^[0-9a-f]{32}$/;
export const plain = (u) => (typeof u === "string" ? u.toLowerCase().replace(/-/g, "") : "");

export async function hmac(secret, text) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function makeToken(env, uuid) {
  const exp = now() + TOKEN_TTL;
  return `${uuid}.${exp}.${await hmac(env.TOKEN_SECRET, `${uuid}.${exp}`)}`;
}

/** The token's account, or null when it's missing, forged or expired. */
export async function check(env, token) {
  if (typeof token !== "string") return null;
  const [uuid, exp, sig] = token.split(".");
  if (!uuidRe.test(uuid || "") || !(Number(exp) > now()) || typeof sig !== "string") return null;
  const want = await hmac(env.TOKEN_SECRET, `${uuid}.${exp}`);
  if (want.length !== sig.length) return null;
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= want.charCodeAt(i) ^ sig.charCodeAt(i);
  return diff === 0 ? uuid : null;
}
