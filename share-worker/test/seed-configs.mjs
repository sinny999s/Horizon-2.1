// Fills a LOCAL Worker (DEV_INSECURE=1) with sample public configs from made-up authors, for trying the browser in the
// dev client:   node test/seed-configs.mjs <modules folder with .hzn configs> [http://127.0.0.1:8787]
// "Bedwars Legit" also carries what a bad config might try (a chat command, Spammer on, a webhook, a number out of
// range), which Horizon must drop when it loads it.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const dir = process.argv[2];
const base = process.argv[3] || "http://127.0.0.1:8787";
if (!base.startsWith("http://127.0.0.1") && !base.startsWith("http://localhost")) throw new Error("local Workers only");

async function post(path, body) {
  const res = await fetch(base + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });
  const out = await res.json().catch(() => ({}));
  if (res.status !== 200) throw new Error(`${path}: ${res.status} ${JSON.stringify(out)}`);
  return out;
}

const uuid = (name) => [...name].map((c) => c.charCodeAt(0).toString(16)).join("").padEnd(32, "7").slice(0, 32);
const tokens = {};
const signIn = async (name) => (tokens[name] ??= (await post("/auth", { uuid: uuid(name) })).token);
const modules = (file) => JSON.parse(readFileSync(join(dir, file), "utf8")).modules;

const bad = [
  { id: "auto-respawn", settings: [{ id: "enabled", value: "true" }, { id: "death-command", value: "CUSTOM" }, { id: "command", value: "/msg everyone EVIL" }] },
  { id: "spammer", settings: [{ id: "enabled", value: "true" }] },
  { id: "message-append", settings: [{ id: "enabled", value: "true" }, { id: "postfix", value: " EVIL" }] },
  { id: "discord-alerts", settings: [{ id: "webhook", value: "https://evil.example/hook" }] },
];
const withBad = (list) => {
  const out = list.filter((m) => !bad.some((b) => b.id === m.id)).map((m) =>
    m.id === "aura" ? { ...m, settings: m.settings.map((s) => (s.id === "range" ? { ...s, value: "99" } : s.id === "enabled" ? { ...s, value: "true" } : s)) } : m);
  return [...out, ...bad];
};

const samples = [
  ["Steve", "Bedwars Legit", "Silent aura that looks human, tuned for Hypixel Bedwars fours. Turn reach down a little for solos.", ["bedwars", "hypixel", "legit"], withBad(modules("bedwars.hzn"))],
  ["Alex", "6b6t Crystal PvP", "Fast crystal aura with surround and offhand totems. Made on 6b6t, works on most anarchy servers.", ["anarchy", "crystal", "pvp"], modules("main.hzn")],
  ["Notch_Fan", "Base Hunting", "Stash finder, new chunks and breadcrumbs for long elytra trips.", ["anarchy", "render", "utility"], modules("hunting.hzn")],
  ["Sky", "Clean Visuals", "Only render modules: ESP, nametags, no clutter.", ["render"], modules("default.hzn")],
  ["Raven", "Blatant Bridge", "Scaffold and speed for bridging. Will get you banned on Hypixel.", ["bedwars", "blatant", "movement"], modules("normalcc.hzn")],
  ["Mia", "Legit Crystal Practice", "", ["crystal", "legit"], modules("main.hzn")],
];

const ids = {};
for (const [author, name, description, tags, data] of samples) {
  const r = await post("/configs/publish", { token: await signIn(author), name, description, tags, data: { modules: data }, author, client: "2.1.15", mc: "26.3" });
  ids[name] = r.config.id;
  console.log(`published ${name} (${r.config.id}): ${r.config.settings} settings, ${r.config.enabled.length} modules on`);
}
// Some stars and loads, so the sorts differ.
const fans = ["Ann", "Ben", "Cat", "Dan", "Eve", "Fay", "Gus"];
const stars = { "Bedwars Legit": 6, "6b6t Crystal PvP": 4, "Base Hunting": 2, "Clean Visuals": 1, "Blatant Bridge": 3 };
for (const [name, n] of Object.entries(stars)) {
  for (const fan of fans.slice(0, n)) await post("/configs/like", { token: await signIn(fan), id: ids[name], like: true });
}
for (const fan of fans) for (const name of ["Bedwars Legit", "6b6t Crystal PvP", "Blatant Bridge"]) await post("/configs/get", { token: await signIn(fan), id: ids[name] });
console.log("seeded", Object.keys(ids).length, "configs");
