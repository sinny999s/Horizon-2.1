/**
 * Horizon share: lets Horizon users see each other's Lunar locker picks (client-side cosmetics included).
 *
 * POST /auth   {uuid, expiresAt, key, keySignature, time, signature} -> {token}   (the account's Mojang-signed chat key
 *                                                       and a fresh challenge signed with it: see cert.js)
 * POST /picks  {token, picks: [ids]}     -> {ok}      (your picks; [] removes them)
 * POST /lookup {token, uuids: [uuid...]} -> {picks: {uuid: [ids]}}   (only accounts that shared picks are in it)
 *
 * Public configs: /configs/... (configs.js).
 *
 * Tokens are HMAC-signed (TOKEN_SECRET, token.js) and last a day. Picks are kept in D1 (binding DB) and dropped after 7 days
 * without an update. Only signed-in Horizon users can look anything up, at most 64 players a request.
 * DEV_INSECURE=1 (local testing only, never set it when deployed) lets /auth take {uuid} without Mojang.
 */
import { provenAccount } from "./cert.js";
import { configs } from "./configs.js";
import { check, makeToken, now, plain, uuidRe } from "./token.js";

const PICK_TTL = 7 * 24 * 3600;
const MAX_BODY = 700 * 1024;
const MAX_PICKS = 24;
const MAX_LOOKUP = 64;

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

async function auth(body, env) {
  if (env.DEV_INSECURE === "1" && body.uuid && !body.signature) {
    const u = plain(body.uuid);
    if (!uuidRe.test(u)) return json({ error: "bad uuid" }, 400);
    return json({ token: await makeToken(env, u) });
  }
  // Mojang's servers can't be asked from here (they answer 403 to Workers), so the account is proven by its
  // Mojang-signed chat key instead.
  const proof = await provenAccount(body);
  if (proof.error) return json({ error: proof.error }, 401);
  const u = proof.uuid;
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
      const text = await req.text();
      if (text.length > MAX_BODY) return json({ error: "too big" }, 413);
      body = JSON.parse(text);
      if (!body || typeof body !== "object") throw new Error();
    } catch (e) {
      return json({ error: "bad json" }, 400);
    }
    if (path === "/auth") return auth(body, env);
    if (path === "/picks") return picks(body, env);
    if (path === "/lookup") return lookup(body, env);
    return (await configs(path, body, env, req)) || json({ error: "not found" }, 404);
  },
};
