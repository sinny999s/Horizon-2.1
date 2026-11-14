# Horizon share

A small Cloudflare Worker for what Horizon users share with each other:
- Lunar locker picks (client-side cosmetics too). Horizon's side: `src/main/kotlin/dev/horizon/client/lunar/HorizonShare.kt`.
- Public configs (`src/configs.js`): publish, browse, load, star and report module configs. Horizon's side:
  `config/PublicConfigs.kt`, `config/PublicConfigData.kt` and `gui/modern/PublicConfigsScreen.kt`.

Both sign in the same way (`src/cert.js`, `src/token.js`). Free Cloudflare plan is enough (Workers + one D1 database).

## Deploy (once)

Run these in this folder (`share-worker/`). You need Node.js (already installed) and a free Cloudflare account.

1. Install the tools:

       npm install

2. Sign in to Cloudflare (opens your browser):

       npx wrangler login

3. Make the database, then put the `database_id` it prints into `wrangler.toml`:

       npx wrangler d1 create horizon-share

4. Make its table:

       npx wrangler d1 execute horizon-share --remote --file=schema.sql

5. Set the token secret (type any long random text when asked; keep it private):

       npx wrangler secret put TOKEN_SECRET

6. Deploy:

       npx wrangler deploy

   It prints the address, like `https://horizon-share.<your-name>.workers.dev`. Put that in
   `HorizonShare.DEFAULT_URL` and rebuild Horizon so every Horizon user gets it (or set `share-url` in a user's
   `horizon/lunar.hzn`).

Never set `DEV_INSECURE` on the deployed Worker: it's only for local tests (`.dev.vars`), where it lets an offline
account sign in without Mojang.

## How it works

- `POST /auth {uuid, expiresAt, key, keySignature, time, signature}`: the account's chat-signing key as Mojang signed it
  (the player certificate) and a fresh challenge signed with it; `src/cert.js` checks both without asking Mojang (Mojang's
  servers answer 403 to Workers, `hasJoined` included) and returns a token for that account (a day, HMAC-signed).
  Mojang's certificate keys are in `cert.js`; if Mojang ever changes them, update the list from
  `https://api.minecraftservices.com/publickeys` (`playerCertificateKeys`) and deploy again.
  Test: `node test/cert-test.mjs ../build/share-auth-fixture.json` after `./gradlew test` (HorizonShareTest writes it).
- `POST /picks {token, picks}`: your locker picks (up to 24 cosmetic ids; `[]` removes them).
- `POST /lookup {token, uuids}`: the picks of those players (up to 64) that are Horizon users with picks shared.
- Picks not updated for 7 days are ignored. Only signed-in accounts can look anything up.

## Test locally

    printf 'TOKEN_SECRET=local-test-secret\nDEV_INSECURE=1\n' > .dev.vars
    npx wrangler d1 execute horizon-share --local --file=schema.sql
    npx wrangler dev --local --port 8787

Then `tools/examples/lunar-share.txt` (its header says how to point the dev client at it).

Public configs: `node test/configs-test.mjs` (the worker running as above, with `--var ADMIN_UUIDS:` plus
`ad` repeated 16 times), and `node test/seed-configs.mjs ../run/horizon/modules` fills it with sample configs for
`tools/examples/public-configs.txt`.

## Public configs: moderation

A config is hidden by itself once 5 accounts report it. Admins are the account ids (32 hex digits without dashes) in
the `ADMIN_UUIDS` secret, comma-separated (`npx wrangler secret put ADMIN_UUIDS`; a secret, so the ids stay out of
this repo). In the game's configs browser an admin gets Reported (the reported and hidden configs, and banned
authors'), each config's report count, and a moderation bar: hide or show it, ban or unban its author, take it down.
Testing locally (`DEV_INSECURE=1`), `--var 'ADMIN_UUIDS:*'` makes everyone an admin. Without the game, from this folder:

    npx wrangler d1 execute horizon-share --remote --command "SELECT id, name, author, reports, hidden FROM configs ORDER BY reports DESC LIMIT 20"
    npx wrangler d1 execute horizon-share --remote --command "UPDATE configs SET hidden = 1 WHERE id = '<id>'"
    npx wrangler d1 execute horizon-share --remote --command "UPDATE authors SET banned = 1 WHERE uuid = '<account id>'"

Updating the database's tables after a change to `schema.sql` (every statement is `IF NOT EXISTS`, so it's safe to
run again): `npx wrangler d1 execute horizon-share --remote --file=schema.sql`, then `npx wrangler deploy`.
