/**
 * Horizon share: lets Horizon users see each other's Lunar locker picks (client-side cosmetics included).
 *
 * POST /auth   {name, serverId}          -> {token}   (the game first joined a Mojang session with serverId; checked
 *                                                       with Mojang's hasJoined, so only the account's owner gets a token)
 * POST /picks  {token, picks: [ids]}     -> {ok}      (your picks; [] removes them)
 * POST /lookup {token, uuids: [uuid...]} -> {picks: {uuid: [ids]}}   (only accounts that shared picks are in it)
 *
 * Tokens are HMAC-signed (TOKEN_SECRET) and last a day. Picks are kept in D1 (binding DB) and dropped after 7 days
 * without an update. Only signed-in Horizon users can look anything up, at most 64 players a request.
 * DEV_INSECURE=1 (local testing only, never set it when deployed) lets /auth take {uuid} without Mojang.
 */
const TOKEN_TTL = 24 * 3600;
const PICK_TTL = 7 * 24 * 3600;
const MAX_PICKS = 24;
const MAX_LOOKUP = 64;

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });
const now = () => Math.floor(Date.now() / 1000);
const uuidRe = /^[0-9a-f]{32}$/;
const plain = (u) => (typeof u === "string" ? u.toLowerCase().replace(/-/g, "") : "");

async function hmac(secret, text) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function makeToken(env, uuid) {
  const exp = now() + TOKEN_TTL;
  return `${uuid}.${exp}.${await hmac(env.TOKEN_SECRET, `${uuid}.${exp}`)}`;
}

/** The token's account, or null when it's forged or expired. */
async function check(env, token) {
  if (typeof token !== "string") return null;
  const [uuid, exp, sig] = token.split(".");
  if (!uuidRe.test(uuid || "") || !(Number(exp) > now()) || typeof sig !== "string") return null;
  const want = await hmac(env.TOKEN_SECRET, `${uuid}.${exp}`);
  if (want.length !== sig.length) return null;
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= want.charCodeAt(i) ^ sig.charCodeAt(i);
  return diff === 0 ? uuid : null;
}

async function auth(body, env) {
  if (env.DEV_INSECURE === "1" && body.uuid) {
    const u = plain(body.uuid);
    if (!uuidRe.test(u)) return json({ error: "bad uuid" }, 400);
    return json({ token: await makeToken(env, u) });
  }
  const name = body.name, serverId = body.serverId;
  if (typeof name !== "string" || !/^[A-Za-z0-9_]{1,16}$/.test(name)) return json({ error: "bad name" }, 400);
  if (typeof serverId !== "string" || !/^-?[0-9a-f]{1,64}$/.test(serverId)) return json({ error: "bad serverId" }, 400);
  const res = await fetch(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`);
  if (res.status !== 200) return json({ error: "not signed in with Mojang" }, 401);
  const profile = await res.json();
  const u = plain(profile.id);
  if (!uuidRe.test(u)) return json({ error: "no profile" }, 401);
  return json({ token: await makeToken(env, u) });
}

async function picks(body, env) {
  const uuid = await check(env, body.token);
  if (!uuid) return json({ error: "bad token" }, 401);
  const list = Array.isArray(body.picks) ? body.picks : [];
  const ids = [...new Set(list.filter((n) => Number.isInteger(n) && n > 0 && n < 1000000))].slice(0, MAX_PICKS);
  if (ids.length === 0) {
    await env.DB.prepare("DELETE FROM picks WHERE uuid = ?").bind(uuid).run();
  } else {
    await env.DB.prepare("INSERT INTO picks (uuid, picks, updated) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET picks = excluded.picks, updated = excluded.updated")
      .bind(uuid, JSON.stringify(ids), now()).run();
  }
  return json({ ok: true, picks: ids });
}

async function lookup(body, env) {
  const uuid = await check(env, body.token);
  if (!uuid) return json({ error: "bad token" }, 401);
  const want = [...new Set((Array.isArray(body.uuids) ? body.uuids : []).map(plain).filter((u) => uuidRe.test(u)))].slice(0, MAX_LOOKUP);
  if (want.length === 0) return json({ picks: {} });
  const marks = want.map(() => "?").join(",");
  const rows = await env.DB.prepare(`SELECT uuid, picks FROM picks WHERE uuid IN (${marks}) AND updated > ?`).bind(...want, now() - PICK_TTL).all();
  const out = {};
  for (const r of rows.results || []) {
    try {
      out[r.uuid] = JSON.parse(r.picks);
    } catch (e) {}
  }
  return json({ picks: out });
}

export default {
  async fetch(req, env) {
    if (!env.TOKEN_SECRET) return json({ error: "TOKEN_SECRET isn't set" }, 500);
    const path = new URL(req.url).pathname;
    if (req.method === "GET" && path === "/") return json({ service: "horizon-share" });
    if (req.method !== "POST") return json({ error: "POST only" }, 405);
    let body;
    try {
      body = await req.json();
    } catch (e) {
      return json({ error: "bad json" }, 400);
    }
    if (path === "/auth") return auth(body, env);
    if (path === "/picks") return picks(body, env);
    if (path === "/lookup") return lookup(body, env);
    return json({ error: "not found" }, 404);
  },
};
