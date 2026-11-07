// Checks cert.js against the body Horizon builds (written by HorizonShareTest to build/share-auth-fixture.json):
//   cd .. && ./gradlew test --tests '*HorizonShareTest*' && cd share-worker && node test/cert-test.mjs ../build/share-auth-fixture.json
import { readFileSync } from "node:fs";
import { provenAccount } from "../src/cert.js";

const f = JSON.parse(readFileSync(process.argv[2], "utf8"));
const keys = [f.mojangKey];
const cases = [
  ["as sent", f, f.uuid],
  ["real Mojang keys only", f, "Mojang didn't sign that key", null],
  ["another account", { ...f, uuid: "0".repeat(32) }, "Mojang didn't sign that key"],
  ["another time", { ...f, time: f.time + 1 }, "bad signature"],
  ["too old", f, "the challenge's time is off", keys, f.now + 6 * 60 * 1000],
  ["expired", { ...f, expiresAt: f.now - 1 }, "the certificate has expired"],
];
let failed = 0;
for (const [name, body, want, k = keys, now = f.now] of cases) {
  const r = await provenAccount(body, k ?? undefined, now);
  const got = r.uuid ?? r.error;
  const ok = got === want;
  if (!ok) failed++;
  console.log(`${ok ? "ok  " : "FAIL"} ${name}: ${got}`);
}
process.exit(failed ? 1 : 0);
