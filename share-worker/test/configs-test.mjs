// Public configs against a local Worker (DEV_INSECURE=1 in .dev.vars, so accounts sign in by uuid):
//   npx wrangler d1 execute horizon-share --local --file=schema.sql
//   npx wrangler dev --port 8787 --var ADMIN_UUIDS:adadadadadadadadadadadadadadadad
//   node test/configs-test.mjs [http://127.0.0.1:8787]
// Each run uses fresh accounts, so it can run again on the same local database.
const base = process.argv[2] || "http://127.0.0.1:8787";
const run = Date.now().toString(16).padStart(12, "0").slice(-12);
const acct = (c) => (c.repeat(20) + run).slice(-32);
let failed = 0;
let passed = 0;

async function post(path, body) {
  const res = await fetch(base + path, { method: "POST", headers: { "content-type": "application/json" }, body: typeof body === "string" ? body : JSON.stringify(body) });
  let out = {};
  try {
    out = await res.json();
  } catch (e) {}
  return { status: res.status, ...out };
}

function expect(name, ok, detail = "") {
  if (ok) passed++;
  else failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${name}${ok ? "" : "  " + JSON.stringify(detail).slice(0, 300)}`);
}

async function signIn(c) {
  const r = await post("/auth", { uuid: acct(c) });
  if (!r.token) throw new Error("sign in failed: " + JSON.stringify(r));
  return r.token;
}

const config = (extra = []) => ({
  modules: [
    { id: "aura", settings: [{ id: "enabled", value: "true" }, { id: "bind", value: '{"key":14}' }, { id: "hidden", value: "false" }, { id: "fov", value: "90" }] },
    { id: "sprint", settings: [{ id: "enabled", value: "false" }, { id: "mode", value: "LEGIT" }] },
    ...extra,
  ],
});

const A = await signIn("a");
const B = await signIn("b");
const admin = await post("/auth", { uuid: "ad".repeat(16) }).then((r) => r.token);
const name = "Bedwars Legit " + run.slice(-4);

// Publishing: who and what.
let r = await post("/configs/publish", { name, data: config() });
expect("publishing needs a sign-in", r.status === 401, r);
r = await post("/configs/publish", { token: A, name: "ab", data: config(), author: "Alpha" });
expect("a name of 2 characters is refused", r.status === 400, r);
r = await post("/configs/publish", { token: A, name, tags: ["bedwars", "nope"], data: config(), author: "Alpha" });
expect("an unknown tag is refused", r.status === 400, r);
r = await post("/configs/publish", { token: A, name, tags: ["bedwars", "hypixel", "legit", "pvp", "render"], data: config(), author: "Alpha" });
expect("5 tags are refused", r.status === 400, r);
r = await post("/configs/publish", { token: A, name, data: { not: "a config" }, author: "Alpha" });
expect("something that isn't a config is refused", r.status === 400, r);
r = await post("/configs/publish", { token: A, name, data: { modules: [{ id: "aura", settings: [{ id: "bind", value: "{}" }] }] }, author: "Alpha" });
expect("a config of only keybinds is empty", r.status === 400, r);
r = await post("/configs/publish", { token: A, name: "  " + name + "\u0007 ", description: "Line one\nline two", tags: ["Bedwars", "legit"], data: config(), author: "Alpha", client: "2.1.15", mc: "26.3" });
expect("publishes", r.status === 200 && r.config && r.config.name === name, r);
const id = r.config.id;
expect("its name is cleaned up", r.config.name === name, r.config.name);
expect("its description is one line", r.config.description === "Line one line two", r.config.description);
expect("tags are lower case", JSON.stringify(r.config.tags) === '["bedwars","legit"]', r.config.tags);
expect("it knows which modules are on", JSON.stringify(r.config.enabled) === '["aura"]', r.config.enabled);
expect("keybinds and Array List choices aren't counted", r.config.settings === 4, r.config.settings);
expect("it's yours", r.config.mine === true && r.config.author.name === "Alpha", r.config);

// Getting one: the data, and downloads counted once a day each.
r = await post("/configs/get", { id });
expect("gets it signed out", r.status === 200 && r.data && r.config.id === id, r);
const settingIds = r.data.modules.flatMap((m) => m.settings.map((s) => s.id));
expect("no keybinds or Array List choices are stored", !settingIds.includes("bind") && !settingIds.includes("hidden"), settingIds);
expect("a download counts", r.config.downloads === 1, r.config.downloads);
r = await post("/configs/get", { id });
expect("the same address again doesn't count", r.config.downloads === 1, r.config.downloads);
r = await post("/configs/get", { token: B, id });
expect("another account counts", r.config.downloads === 2, r.config.downloads);
r = await post("/configs/get", { id: "nope" });
expect("a bad id is refused", r.status === 400, r);
r = await post("/configs/get", { id: "ZZZZZZZZ" });
expect("an unknown id is not found", r.status === 404, r);

// Same name again (any case) updates it.
r = await post("/configs/publish", { token: A, name: name.toUpperCase(), data: config([{ id: "flight", settings: [{ id: "enabled", value: "true" }] }]), author: "Alpha" });
expect("the same name updates it", r.status === 200 && r.config.id === id && r.config.enabled.length === 2, r);

// Lists.
r = await post("/configs/list", { q: name.toLowerCase() });
expect("search finds it by name", r.configs && r.configs.some((c) => c.id === id), r);
r = await post("/configs/list", { q: "alpha" });
expect("search finds it by author", r.configs && r.configs.some((c) => c.id === id), r.configs && r.configs.length);
r = await post("/configs/list", { q: "100%_" });
expect("search's wildcards are plain text", r.status === 200 && r.configs.length === 0, r);
r = await post("/configs/list", { tag: "bedwars", sort: "recent" });
expect("the tag finds it", r.configs.some((c) => c.id === id), r.configs.length);
r = await post("/configs/list", { tag: "anarchy" });
expect("another tag doesn't", !r.configs.some((c) => c.id === id), r.configs.length);
r = await post("/configs/list", { tag: "nope" });
expect("an unknown tag is refused", r.status === 400, r);
r = await post("/configs/list", { mine: true });
expect("yours needs a sign-in", r.status === 401, r);
r = await post("/configs/list", { token: A, mine: true });
expect("yours", r.configs.length === 1 && r.configs[0].id === id && !("data" in r.configs[0]), r);

// Likes.
r = await post("/configs/like", { token: B, id, like: true });
expect("likes", r.likes === 1 && r.liked === true, r);
r = await post("/configs/like", { token: B, id, like: true });
expect("liking twice counts once", r.likes === 1, r);
r = await post("/configs/list", { token: B, q: name.toLowerCase() });
expect("the list says you liked it", r.configs[0] && r.configs[0].liked === true && r.configs[0].likes === 1, r.configs[0]);
r = await post("/configs/like", { token: B, id, like: false });
expect("unlikes", r.likes === 0 && r.liked === false, r);
r = await post("/configs/like", { id });
expect("liking needs a sign-in", r.status === 401, r);

// Deleting: only the author.
r = await post("/configs/delete", { token: B, id });
expect("someone else can't delete it", r.status === 404, r);

// Reports hide it at 5 reporters.
r = await post("/configs/report", { token: A, id, reason: "mine" });
expect("you can't report your own", r.status === 400, r);
for (const c of ["c", "d", "e", "f"]) await post("/configs/report", { token: await signIn(c), id, reason: "spam" });
r = await post("/configs/get", { id });
expect("4 reports leave it up", r.status === 200, r);
await post("/configs/report", { token: B, id, reason: "spam\u0000" });
await post("/configs/report", { token: B, id, reason: "again" });
r = await post("/configs/get", { id });
expect("5 reporters hide it", r.status === 404, r);
r = await post("/configs/list", { q: name.toLowerCase() });
expect("a hidden one isn't listed", r.configs.length === 0, r.configs);
r = await post("/configs/list", { token: A, mine: true });
expect("its author still sees it", r.configs.length === 1, r.configs);
r = await post("/configs/list", { token: admin, q: name.toLowerCase() });
expect("an admin sees it, with its reports", r.configs.length === 1 && r.configs[0].hidden === true && r.configs[0].reports === 5, r.configs);
r = await post("/configs/hide", { token: A, id, hidden: false });
expect("only admins unhide", r.status === 403, r);
r = await post("/configs/hide", { token: admin, id, hidden: false });
r = await post("/configs/get", { id });
expect("an admin unhides it", r.status === 200, r);

// Bans.
r = await post("/configs/publish", { token: B, name: "Bravo's " + run.slice(-4), data: config(), author: "Bravo" });
const bravo = r.config && r.config.id;
expect("another author publishes", r.status === 200, r);
r = await post("/configs/ban", { token: admin, uuid: acct("b"), banned: true });
r = await post("/configs/publish", { token: B, name: "Again " + run.slice(-4), data: config(), author: "Bravo" });
expect("a banned author can't publish", r.status === 403, r);
r = await post("/configs/get", { id: bravo });
expect("a banned author's configs are hidden", r.status === 404, r);
await post("/configs/ban", { token: admin, uuid: acct("b"), banned: false });
r = await post("/configs/get", { id: bravo });
expect("unbanned, they're back", r.status === 200, r);

// What admins get.
r = await post("/configs/list", { token: admin, q: name.toLowerCase() });
expect("the list tells an admin they are one", r.admin === true, r.admin);
r = await post("/configs/list", { token: A, q: name.toLowerCase() });
expect("and others they aren't, without the reports", r.admin === false && r.configs[0] && !("reports" in r.configs[0]), r);
r = await post("/configs/list", { token: A, reported: true });
expect("only admins see the reported list", r.status === 403, r);
await post("/configs/ban", { token: admin, uuid: acct("b"), banned: true });
r = await post("/configs/list", { token: admin, reported: true });
const reported = r.configs || [];
expect("the reported list has the reported config and the banned author's",
  reported.some((c) => c.id === id && c.reports === 5) && reported.some((c) => c.id === bravo && c.authorBanned === true),
  reported.map((c) => [c.name, c.reports, c.hidden, c.authorBanned]));
await post("/configs/ban", { token: admin, uuid: acct("b"), banned: false });
r = await post("/configs/delete", { token: admin, id: bravo });
expect("an admin takes down someone else's", r.ok === true, r);
r = await post("/configs/get", { id: bravo });
expect("and it's gone", r.status === 404, r);

// Sizes.
const big = { modules: Array.from({ length: 700 }, (_, i) => ({ id: "m" + i, settings: Array.from({ length: 30 }, (_, j) => ({ id: "s" + j, value: "x".repeat(20) })) })) };
r = await post("/configs/publish", { token: A, name: "Huge " + run.slice(-4), data: big, author: "Alpha" });
expect("a config over the size cap is refused", r.status === 413, r);
r = await post("/configs/publish", "x".repeat(800 * 1024));
expect("a body over the cap is refused", r.status === 413, r);
r = await post("/configs/list", "not json");
expect("bad json is refused", r.status === 400, r);

// Publishing is limited per hour.
let limited = 0;
for (let i = 0; i < 14 && !limited; i++) {
  r = await post("/configs/publish", { token: A, name: `Burst ${i} ${run.slice(-4)}`, data: config(), author: "Alpha" });
  if (r.status === 429) limited = i;
}
expect("publishing is limited per hour", limited > 0 && limited <= 12, { limited, last: r });

// Deleting yours.
r = await post("/configs/delete", { token: A, id });
expect("you delete yours", r.ok === true, r);
r = await post("/configs/get", { id });
expect("then it's gone", r.status === 404, r);

// The locker sharing still works.
r = await post("/picks", { token: A, picks: [5, 9] });
expect("picks still go up", r.ok === true, r);
r = await post("/lookup", { token: B, uuids: [acct("a")] });
expect("and are looked up", JSON.stringify(r.picks && r.picks[acct("a")]) === "[5,9]", r);
r = await post("/configs/nope", {});
expect("an unknown route is not found", r.status === 404, r);

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
