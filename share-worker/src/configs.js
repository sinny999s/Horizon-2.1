/**
 * Public configs: Horizon users publish their module configs and browse, load, like and report each other's.
 *
 * POST /configs/list    {token?, q?, tag?, sort?, page?, mine?, reported?} -> {configs: [meta], more, admin}
 *                       (reported: admins only, the reported and hidden ones first)
 * POST /configs/get     {token?, id}                             -> {config: meta, data}   (counts a download)
 * POST /configs/publish {token, name, description?, tags?, data, author, client?, mc?} -> {config: meta}
 *                       (one of yours with the same name is updated instead, keeping its description and tags
 *                       unless new ones are given)
 * POST /configs/delete  {token, id}                              -> {ok}      (yours; any, for an admin)
 * POST /configs/like    {token, id, like}                        -> {likes, liked}
 * POST /configs/report  {token, id, reason?}                     -> {ok}      (hidden at REPORTS_TO_HIDE reporters)
 * POST /configs/hide    {token, id, hidden}                      -> {ok}      (admins)
 * POST /configs/ban     {token, uuid, banned}                    -> {ok}      (admins; a banned author's configs are hidden)
 *
 * A config's data is {modules: [{id, settings: [{id, value}]}]}, as Horizon saves one; keybinds and Array List
 * choices are dropped here, and Horizon itself drops anything personal (text settings and the like) before it uploads
 * and again before it loads one, so a config can't carry or plant anything but settings. Admins: the account ids in
 * ADMIN_UUIDS (comma-separated).
 */
import { check, now, plain, uuidRe } from "./token.js";

export const TAGS = ["bedwars", "hypixel", "anarchy", "pvp", "crystal", "legit", "blatant", "movement", "render", "utility"];
const MAX_TAGS = 4;
const MAX_DATA = 512 * 1024;
const MAX_MODULES = 800;
const MAX_SETTINGS = 600;
const MAX_VALUE = 4096;
const MAX_PER_AUTHOR = 25;
const PUBLISH_PER_HOUR = 12;
const REPORTS_TO_HIDE = 5;
const PAGE = 24;
const SORTS = {
  popular: "(c.likes * 5 + c.downloads) DESC, c.updated DESC",
  top: "c.likes DESC, c.downloads DESC",
  recent: "c.updated DESC",
};
const idRe = /^[A-Za-z0-9]{8}$/;
const keyRe = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;
const nameRe = /^[A-Za-z0-9_]{1,16}$/;

class Refusal extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
const refuse = (status, message) => {
  throw new Refusal(status, message);
};

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

/** One line of plain text: control characters out, runs of space as one, at most [max] characters. */
function line(s, max) {
  if (typeof s !== "string") return "";
  return s.replace(/[\u0000-\u001f\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2066-\u2069]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}

function randomId() {
  const abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return [...bytes].map((b) => abc[b % abc.length]).join("");
}

async function sha(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

const admins = (env) => new Set(String(env.ADMIN_UUIDS || "").split(",").map(plain).filter((u) => uuidRe.test(u)));

/** Whether [me] is an admin. Testing locally (DEV_INSECURE=1), ADMIN_UUIDS "*" makes everyone one. */
const isAdmin = (env, me) => !!me && ((env.DEV_INSECURE === "1" && env.ADMIN_UUIDS === "*") || admins(env).has(me));

/**
 * The config's settings as stored: known shapes only, keybinds and Array List choices left out. Also which modules it
 * turns on and how many settings it has. Refuses what is too big or not a config.
 */
export function cleanData(data) {
  if (!data || typeof data !== "object" || !Array.isArray(data.modules)) refuse(400, "that isn't a config");
  if (data.modules.length > MAX_MODULES) refuse(400, "too many modules");
  const modules = [];
  const enabled = [];
  let settings = 0;
  for (const m of data.modules) {
    if (!m || typeof m.id !== "string" || !keyRe.test(m.id) || !Array.isArray(m.settings)) continue;
    if (m.settings.length > MAX_SETTINGS) refuse(400, "too many settings in " + m.id);
    const list = [];
    for (const s of m.settings) {
      if (!s || typeof s.id !== "string" || !keyRe.test(s.id) || typeof s.value !== "string") continue;
      if (s.id === "bind" || s.id === "hidden" || s.value.length > MAX_VALUE) continue;
      list.push({ id: s.id, value: s.value });
      if (s.id === "enabled" && s.value === "true") enabled.push(m.id);
    }
    if (list.length === 0) continue;
    settings += list.length;
    modules.push({ id: m.id, settings: list });
  }
  if (settings === 0) refuse(400, "the config is empty");
  return { modules, enabled, settings };
}

function meta(r, me, liked, admin) {
  return {
    id: r.id,
    name: r.name,
    description: r.description,
    tags: JSON.parse(r.tags),
    author: { uuid: r.author, name: r.author_name },
    downloads: r.downloads,
    likes: r.likes,
    enabled: JSON.parse(r.enabled),
    settings: r.settings,
    size: r.size,
    client: r.client,
    mc: r.mc,
    created: r.created,
    updated: r.updated,
    liked: !!liked,
    mine: me === r.author,
    ...(admin ? { reports: r.reports, hidden: !!r.hidden, authorBanned: !!r.author_banned } : {}),
  };
}

const SELECT = "SELECT c.*, a.name AS author_name, a.banned AS author_banned FROM configs c JOIN authors a ON a.uuid = c.author";

async function row(env, id) {
  if (typeof id !== "string" || !idRe.test(id)) refuse(400, "bad id");
  return env.DB.prepare(`${SELECT} WHERE c.id = ?`).bind(id).first();
}

/** Whether [me] may see [r]: a hidden one or a banned author's only to its author and admins. */
const visible = (r, me, admin) => !!r && (admin || r.author === me || (!r.hidden && !r.author_banned));

async function list(body, env, me, admin) {
  const where = [];
  const args = [];
  if (body.mine) {
    if (!me) refuse(401, "sign in to see yours");
    where.push("c.author = ?");
    args.push(me);
  } else if (!admin) {
    where.push("c.hidden = 0 AND a.banned = 0");
  }
  if (body.reported) {
    if (!admin) refuse(403, "admins only");
    where.push("(c.reports > 0 OR c.hidden = 1 OR a.banned = 1)");
  }
  const tag = typeof body.tag === "string" ? body.tag.toLowerCase() : "";
  if (tag) {
    if (!TAGS.includes(tag)) refuse(400, "unknown tag");
    where.push("c.tags LIKE ?");
    args.push(`%"${tag}"%`);
  }
  const q = line(body.q, 48).toLowerCase();
  if (q) {
    const like = "%" + q.replace(/[\\%_]/g, (c) => "\\" + c) + "%";
    where.push("(lower(c.name) LIKE ? ESCAPE '\\' OR lower(c.description) LIKE ? ESCAPE '\\' OR lower(a.name) LIKE ? ESCAPE '\\')");
    args.push(like, like, like);
  }
  const sort = body.reported ? "c.hidden DESC, c.reports DESC, c.updated DESC" : SORTS[body.sort] || SORTS.popular;
  const page = Number.isInteger(body.page) && body.page >= 0 && body.page < 1000 ? body.page : 0;
  const sql = `${SELECT}${where.length ? " WHERE " + where.join(" AND ") : ""} ORDER BY ${sort} LIMIT ? OFFSET ?`;
  const rows = (await env.DB.prepare(sql).bind(...args, PAGE + 1, page * PAGE).all()).results || [];
  const shown = rows.slice(0, PAGE);
  let liked = new Set();
  if (me && shown.length) {
    const marks = shown.map(() => "?").join(",");
    const got = await env.DB.prepare(`SELECT id FROM config_likes WHERE uuid = ? AND id IN (${marks})`).bind(me, ...shown.map((r) => r.id)).all();
    liked = new Set((got.results || []).map((r) => r.id));
  }
  return json({ configs: shown.map((r) => meta(r, me, liked.has(r.id), admin)), more: rows.length > PAGE, admin });
}

async function get(body, env, me, admin, req) {
  const r = await row(env, body.id);
  if (!visible(r, me, admin)) refuse(404, "no such config");
  // A download counts once a day per account (or per address, signed out).
  const who = me || "ip:" + (await sha("horizon-configs " + (req.headers.get("CF-Connecting-IP") || "local"))).slice(0, 24);
  const day = Math.floor(now() / 86400);
  const got = await env.DB.prepare("INSERT OR IGNORE INTO config_downloads (id, who, day) VALUES (?, ?, ?)").bind(r.id, who, day).run();
  if (got.meta && got.meta.changes > 0) {
    await env.DB.prepare("UPDATE configs SET downloads = downloads + 1 WHERE id = ?").bind(r.id).run();
    r.downloads += 1;
  }
  const liked = me ? await env.DB.prepare("SELECT 1 FROM config_likes WHERE id = ? AND uuid = ?").bind(r.id, me).first() : null;
  return json({ config: meta(r, me, liked, admin), data: JSON.parse(r.data), admin });
}

async function publish(body, env, me, admin) {
  if (!me) refuse(401, "sign in to publish");
  const author = await env.DB.prepare("SELECT * FROM authors WHERE uuid = ?").bind(me).first();
  if (author && author.banned) refuse(403, "you can't publish configs");
  const name = line(body.name, 32);
  if (name.length < 3) refuse(400, "give it a name of 3 to 32 characters");
  let description = line(body.description, 300);
  let tags = [...new Set((Array.isArray(body.tags) ? body.tags : []).map((t) => String(t).toLowerCase()))];
  if (tags.some((t) => !TAGS.includes(t))) refuse(400, "unknown tag");
  if (tags.length > MAX_TAGS) refuse(400, `at most ${MAX_TAGS} tags`);
  const authorName = typeof body.author === "string" && nameRe.test(body.author) ? body.author : author ? author.name : "?";
  const clean = cleanData(body.data);
  const data = JSON.stringify({ modules: clean.modules });
  if (data.length > MAX_DATA) refuse(413, "the config is too big");
  const t = now();
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM config_actions WHERE uuid = ? AND at > ?").bind(me, t - 3600).first();
  if (!admin && recent.n >= PUBLISH_PER_HOUR) refuse(429, "you've published a lot this hour; try again later");
  const key = name.toLowerCase();
  const same = await env.DB.prepare("SELECT id, description, tags FROM configs WHERE author = ? AND name_key = ?").bind(me, key).first();
  if (!same) {
    const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM configs WHERE author = ?").bind(me).first();
    if (!admin && count.n >= MAX_PER_AUTHOR) refuse(403, `you can have ${MAX_PER_AUTHOR} public configs; delete one first`);
  }
  // Updating one: a description or tags left out stay as they were.
  if (same && typeof body.description !== "string") description = same.description;
  if (same && !Array.isArray(body.tags)) tags = JSON.parse(same.tags);
  const id = same ? same.id : randomId();
  const client = line(body.client, 24);
  const mc = line(body.mc, 24);
  await env.DB.batch([
    env.DB.prepare("INSERT INTO authors (uuid, name, banned, updated) VALUES (?, ?, 0, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, updated = excluded.updated")
      .bind(me, authorName, t),
    same
      ? env.DB.prepare("UPDATE configs SET name = ?, description = ?, tags = ?, data = ?, enabled = ?, settings = ?, size = ?, client = ?, mc = ?, updated = ? WHERE id = ?")
          .bind(name, description, JSON.stringify(tags), data, JSON.stringify(clean.enabled), clean.settings, data.length, client, mc, t, id)
      : env.DB.prepare("INSERT INTO configs (id, author, name, name_key, description, tags, data, enabled, settings, size, client, mc, created, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
          .bind(id, me, name, key, description, JSON.stringify(tags), data, JSON.stringify(clean.enabled), clean.settings, data.length, client, mc, t, t),
    env.DB.prepare("INSERT INTO config_actions (uuid, at) VALUES (?, ?)").bind(me, t),
    env.DB.prepare("DELETE FROM config_actions WHERE at < ?").bind(t - 86400),
  ]);
  const r = await row(env, id);
  return json({ config: meta(r, me, false, admin) });
}

async function remove(body, env, me, admin) {
  if (!me) refuse(401, "sign in first");
  const r = await row(env, body.id);
  if (!r || (r.author !== me && !admin)) refuse(404, "no such config of yours");
  await env.DB.batch([
    env.DB.prepare("DELETE FROM configs WHERE id = ?").bind(r.id),
    env.DB.prepare("DELETE FROM config_likes WHERE id = ?").bind(r.id),
    env.DB.prepare("DELETE FROM config_reports WHERE id = ?").bind(r.id),
    env.DB.prepare("DELETE FROM config_downloads WHERE id = ?").bind(r.id),
  ]);
  return json({ ok: true });
}

async function like(body, env, me, admin) {
  if (!me) refuse(401, "sign in to like configs");
  const r = await row(env, body.id);
  if (!visible(r, me, admin)) refuse(404, "no such config");
  const on = body.like !== false;
  const sql = on ? "INSERT OR IGNORE INTO config_likes (id, uuid) VALUES (?, ?)" : "DELETE FROM config_likes WHERE id = ? AND uuid = ?";
  await env.DB.prepare(sql).bind(r.id, me).run();
  const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM config_likes WHERE id = ?").bind(r.id).first();
  await env.DB.prepare("UPDATE configs SET likes = ? WHERE id = ?").bind(n.n, r.id).run();
  return json({ likes: n.n, liked: on });
}

async function report(body, env, me, admin) {
  if (!me) refuse(401, "sign in to report configs");
  const r = await row(env, body.id);
  if (!visible(r, me, admin)) refuse(404, "no such config");
  if (r.author === me) refuse(400, "that's yours");
  await env.DB.prepare("INSERT OR IGNORE INTO config_reports (id, uuid, reason, at) VALUES (?, ?, ?, ?)").bind(r.id, me, line(body.reason, 200), now()).run();
  const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM config_reports WHERE id = ?").bind(r.id).first();
  await env.DB.prepare("UPDATE configs SET reports = ?, hidden = CASE WHEN ? >= ? THEN 1 ELSE hidden END WHERE id = ?")
    .bind(n.n, n.n, REPORTS_TO_HIDE, r.id).run();
  return json({ ok: true });
}

async function hide(body, env, me, admin) {
  if (!admin) refuse(403, "admins only");
  const r = await row(env, body.id);
  if (!r) refuse(404, "no such config");
  await env.DB.prepare("UPDATE configs SET hidden = ? WHERE id = ?").bind(body.hidden === false ? 0 : 1, r.id).run();
  return json({ ok: true });
}

async function ban(body, env, me, admin) {
  if (!admin) refuse(403, "admins only");
  const u = plain(body.uuid);
  if (!uuidRe.test(u)) refuse(400, "bad uuid");
  await env.DB.prepare("INSERT INTO authors (uuid, name, banned, updated) VALUES (?, '?', ?, ?) ON CONFLICT(uuid) DO UPDATE SET banned = excluded.banned")
    .bind(u, body.banned === false ? 0 : 1, now()).run();
  return json({ ok: true });
}

const ROUTES = { list, get, publish, delete: remove, like, report, hide, ban };

/** Handles /configs/<route>; null for any other path. */
export async function configs(path, body, env, req) {
  const name = path.startsWith("/configs/") ? path.slice(9) : null;
  const route = name && Object.hasOwn(ROUTES, name) ? ROUTES[name] : null;
  if (!route) return null;
  try {
    const me = await check(env, body.token);
    return await route(body, env, me, isAdmin(env, me), req);
  } catch (e) {
    if (e instanceof Refusal) return json({ error: e.message }, e.status);
    throw e;
  }
}
